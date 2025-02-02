package org.openstreetmap.josm.plugins.dl.russiaaddresshelper.models

import com.github.kittinunf.fuel.core.Request
import com.github.kittinunf.fuel.core.Response
import com.github.kittinunf.fuel.core.isSuccessful
import com.github.kittinunf.result.Result
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.apache.commons.text.StringEscapeUtils
import org.openstreetmap.josm.command.ChangePropertyCommand
import org.openstreetmap.josm.command.Command
import org.openstreetmap.josm.command.SequenceCommand
import org.openstreetmap.josm.data.UndoRedoHandler
import org.openstreetmap.josm.data.coor.EastNorth
import org.openstreetmap.josm.data.osm.OsmPrimitive
import org.openstreetmap.josm.data.osm.Way
import org.openstreetmap.josm.plugins.dl.russiaaddresshelper.RussiaAddressHelperPlugin
import org.openstreetmap.josm.plugins.dl.russiaaddresshelper.tools.DeleteDoubles
import org.openstreetmap.josm.plugins.dl.russiaaddresshelper.io.EgrnSettingsReader
import org.openstreetmap.josm.plugins.dl.russiaaddresshelper.io.TagSettingsReader
import org.openstreetmap.josm.plugins.dl.russiaaddresshelper.parsers.HouseNumberParser
import org.openstreetmap.josm.plugins.dl.russiaaddresshelper.parsers.StreetParser
import org.openstreetmap.josm.tools.Geometry
import org.openstreetmap.josm.tools.I18n
import org.openstreetmap.josm.tools.Logging

// FIXME: такой молодой, а уже легаси...
class Buildings(objects: List<OsmPrimitive>) {
    private val scope: CoroutineScope = CoroutineScope(CoroutineName("EGRN requests"))

    val size: Int
        get() {
            return items.size
        }

    class LoadListener {
        var onResponse: ((res: Response) -> Unit)? = null
        var onResponseContinue: (() -> Unit)? = null
        var onNotFoundStreetParser: ((street: String) -> Unit)? = null
        var onComplete: ((changeBuildings: Array<OsmPrimitive>) -> Unit)? = null
    }

    class Building(val osmPrimitive: OsmPrimitive) {
        private val coordinate: EastNorth?
            get() {
                return when (osmPrimitive) {
                    is Way -> {
                        Geometry.getCentroid(osmPrimitive.nodes)
                    }
                    else -> {
                        null
                    }
                }
            }

        val preparedTags: MutableMap<String, String> = mutableMapOf()

        fun request(): Request {
            return RussiaAddressHelperPlugin.getEgrnClient().request(coordinate!!)
        }
    }

    private var items: MutableList<Building> = mutableListOf()

    init {
        objects.forEach {
            items.add(Building(it))
        }
    }

    fun isNotEmpty(): Boolean {
        return items.isNotEmpty()
    }

    fun load(loadListener: LoadListener? = null): CoroutineScope {
        scope.launch {
            val channel = requests(loadListener)

            parseResponses(channel, loadListener).awaitAll()

            sanitize()

            val changeBuildings: MutableList<OsmPrimitive> = mutableListOf()

            if (items.size > 0) {
                val cmds: MutableList<Command> = mutableListOf()

                for (building in items) {
                    building.preparedTags.forEach { (key, value) ->
                        cmds.add(ChangePropertyCommand(building.osmPrimitive, key, value))

                        if (!changeBuildings.contains(building.osmPrimitive)) {
                            changeBuildings.add(building.osmPrimitive)
                        }
                    }
                }

                if (cmds.size > 0) {
                    val c: Command = SequenceCommand(I18n.tr("Added tags from RussiaAddressHelper "), cmds)
                    UndoRedoHandler.getInstance().add(c)
                }
            }

            loadListener?.onComplete?.invoke(changeBuildings.toTypedArray())
        }
        return scope
    }

    data class ChannelData(val building: Building, val responseBody: String)

    private fun requests(loadListener: LoadListener? = null): Channel<ChannelData> {
        val limit = EgrnSettingsReader.REQUEST_LIMIT.get()
        val semaphore = kotlinx.coroutines.sync.Semaphore(limit)
        val channel = Channel<ChannelData>()

        items.mapIndexed { index, building ->
            scope.launch {
                try {
                    semaphore.acquire()

                    try {
                        val (_, response, result) = building.request().responseString()

                        when (result) {
                            is Result.Success -> {
                                if (!channel.isClosedForSend) {
                                    if (response.isSuccessful) {
                                        channel.send(ChannelData(building, result.get()))
                                    }

                                    loadListener?.onResponse?.invoke(response)

                                    if (items.size - 1 == index) {
                                        loadListener?.onResponseContinue?.invoke()
                                        channel.close()
                                    } else if (items.size - limit >= index) {
                                        delay((EgrnSettingsReader.REQUEST_DELAY.get() * 1000).toLong())
                                    }
                                }
                            }
                            is Result.Failure -> {
                                Logging.warn(result.getException())

                                if (items.size - 1 == index) {
                                    loadListener?.onResponseContinue?.invoke()
                                    channel.close()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Logging.warn(e.message)
                    }

                } finally {
                    if (scope.isActive) semaphore.release()
                }
            }
        }

        return channel
    }

    private suspend fun parseResponses(channel: Channel<ChannelData>, loadListener: LoadListener? = null): MutableList<Deferred<Void?>> {
        val defers: MutableList<Deferred<Void?>> = mutableListOf()
        val streetParser = StreetParser()
        val houseNumberParser = HouseNumberParser()

        for (d in channel) {
            defers += scope.async {
                val match = Regex("""address":\s"(.+?)"""").find(StringEscapeUtils.unescapeJson(d.responseBody))

                if (match == null) {
                    Logging.error("Parse EGRN response error.")
                } else {
                    val address = match.groupValues[1]
                    val osmPrimitive = d.building.osmPrimitive

                    if (TagSettingsReader.EGRN_ADDR_RECORD.get() && !osmPrimitive.hasTag("addr:RU:egrn")) {
                        d.building.preparedTags["addr:RU:egrn"] = address
                    }

                    val streetParse = streetParser.parse(address)
                    val houseNumberParse = houseNumberParser.parse(address)

                    if (streetParse.name != "") {
                        if (houseNumberParse != "") {
                            d.building.preparedTags["addr:housenumber"] = houseNumberParse
                            d.building.preparedTags["addr:street"] = streetParse.name

                            if (!osmPrimitive.hasTag("addr:housenumber")) {
                                d.building.preparedTags["source:addr"] = "ЕГРН"
                            }
                        }
                    } else if (streetParse.extracted != "") {
                        loadListener?.onNotFoundStreetParser?.invoke(streetParse.extracted)
                    }
                }

                null
            }
        }

        return defers
    }

    private fun sanitize() {
        items.removeAll { it.preparedTags.isEmpty() }

        if (TagSettingsReader.ENABLE_CLEAR_DOUBLE.get()) {
            items = DeleteDoubles().clear(items)
        }
    }
}