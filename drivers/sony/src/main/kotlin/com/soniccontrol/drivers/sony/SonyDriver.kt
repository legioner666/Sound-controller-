package com.soniccontrol.drivers.sony

import com.soniccontrol.core.devices.BaseDriverSession
import com.soniccontrol.core.devices.BluetoothTransport
import com.soniccontrol.core.devices.Brand
import com.soniccontrol.core.devices.CatalogEntry
import com.soniccontrol.core.devices.CatalogNote
import com.soniccontrol.core.devices.CommandExecutor
import com.soniccontrol.core.devices.CommandGuard
import com.soniccontrol.core.devices.CommandKind
import com.soniccontrol.core.devices.CommandOutcome
import com.soniccontrol.core.devices.DataSource
import com.soniccontrol.core.devices.DeviceCapabilities
import com.soniccontrol.core.devices.DeviceCommand
import com.soniccontrol.core.devices.DeviceIdentity
import com.soniccontrol.core.devices.DeviceLink
import com.soniccontrol.core.devices.DriverEnvironment
import com.soniccontrol.core.devices.DriverMatch
import com.soniccontrol.core.devices.DriverSession
import com.soniccontrol.core.devices.Feature
import com.soniccontrol.core.devices.FeatureSupport
import com.soniccontrol.core.devices.FramedTransport
import com.soniccontrol.core.devices.HeadphoneDriver
import com.soniccontrol.core.devices.HeadphoneState
import com.soniccontrol.core.devices.NoiseMode
import com.soniccontrol.core.devices.NoiseStatus
import com.soniccontrol.core.devices.ResponseMatcher
import com.soniccontrol.core.devices.StandardUuids
import com.soniccontrol.core.devices.SupportStatus
import com.soniccontrol.core.devices.TransportState
import com.soniccontrol.core.diagnostics.EventCategory
import com.soniccontrol.core.protocol.toHex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Presents MDR as payload-in / payload-out: frames, sequence numbers and ACKs are handled here.
 * Every received command frame is acknowledged; ACKs are not acknowledged.
 * [incoming] emits the decoded payload of command frames only.
 */
internal class SonyTransport(private val framed: BluetoothTransport, scope: CoroutineScope, private val onRaw: (String, ByteArray) -> Unit) : BluetoothTransport {
    private val payloads = MutableSharedFlow<ByteArray>(extraBufferCapacity = 32)
    private val writeLock = Mutex()
    private var seq = 0

    init {
        scope.launch {
            framed.incoming.collect { raw ->
                val f = SonyMdr.decode(raw) ?: return@collect onRaw("undecodable MDR frame", raw)
                if (f.type == SonyMdr.TYPE_COMMAND || f.type == SonyMdr.TYPE_COMMAND_2) {
                    writeLock.withLock { framed.write(SonyMdr.ack(f.seq)) }
                    payloads.emit(f.payload)
                }
            }
        }
    }

    override val description get() = "Sony MDR"
    override val state: StateFlow<TransportState> get() = framed.state
    override val incoming: SharedFlow<ByteArray> get() = payloads
    override val canWrite get() = true
    override suspend fun close() = framed.close()

    override suspend fun write(bytes: ByteArray) = writeLock.withLock {
        framed.write(SonyMdr.encode(SonyMdr.TYPE_COMMAND, seq, bytes))
        seq = 1 - seq
    }
}

object SonyGuard : CommandGuard {
    override fun refusal(command: DeviceCommand) = if (SonyMdr.isAllowedPayload(command.bytes)) null else "payload not on the Sony allowlist"
}

class SonyDriver : HeadphoneDriver {
    override val id = "sony"
    override val brand = Brand.SONY
    override val displayName = "Sony"

    private data class Model(val name: String, val v1: Boolean, val status: SupportStatus, val anc: Boolean = true)

    private val models = listOf(
        Model("WH-1000XM6", false, SupportStatus.SUPPORTED),
        Model("WH-1000XM5", false, SupportStatus.SUPPORTED),
        Model("WH-CH720N", false, SupportStatus.SUPPORTED),
        Model("WH-1000XM4", true, SupportStatus.SUPPORTED),
        Model("WF-1000XM5", false, SupportStatus.EXPERIMENTAL),
        Model("WF-1000XM4", false, SupportStatus.EXPERIMENTAL),
        Model("LinkBuds S", false, SupportStatus.EXPERIMENTAL),
        Model("WF-C700N", false, SupportStatus.EXPERIMENTAL),
        Model("ULT WEAR", false, SupportStatus.EXPERIMENTAL),
        Model("WH-CH520", false, SupportStatus.BASIC, anc = false),
    )

    override val catalog: List<CatalogEntry> = models.map { m ->
        CatalogEntry(
            Brand.SONY, m.name, id, if (m.v1) "Sony MDR v1 (RFCOMM)" else "Sony MDR v2 (RFCOMM)",
            buildMap {
                put(Feature.CONNECTION_STATUS, SupportStatus.BASIC); put(Feature.SYSTEM_VOLUME, SupportStatus.BASIC)
                put(Feature.MEDIA_CONTROL, SupportStatus.BASIC); put(Feature.BATTERY, SupportStatus.SUPPORTED)
                if (m.anc) put(Feature.NOISE_CONTROL, m.status)
            },
            aliases = listOf("Sony ${m.name}"),
            note = if (m.status == SupportStatus.SUPPORTED) CatalogNote.CONFIRMED_ON_HARDWARE else null,
        )
    }

    init {
        StandardUuids.register(SonyMdr.V2_UUID, "Sony MDR v2")
        StandardUuids.register(SonyMdr.V1_UUID, "Sony MDR v1")
    }

    private fun model(name: String?) = name?.let { n -> models.firstOrNull { n.equals(it.name, true) || n.equals("Sony ${it.name}", true) } }

    override fun match(identity: DeviceIdentity): DriverMatch? {
        model(identity.name)?.let { m -> return DriverMatch(90, catalog.first { it.model == m.name }, "catalog name match") }
        if (SonyMdr.V2_UUID in identity.sdpUuids || SonyMdr.V1_UUID in identity.sdpUuids) return DriverMatch(75, null, "Sony MDR SDP record")
        val n = identity.name ?: return null
        if (Regex("^(WH|WF|WI)-[A-Z0-9]+").containsMatchIn(n) || n.startsWith("Sony", true) || n.startsWith("LinkBuds", true)) {
            return DriverMatch(45, null, "Sony name pattern")
        }
        return null
    }

    override fun createSession(link: DeviceLink, identity: DeviceIdentity, env: DriverEnvironment): DriverSession {
        val m = model(identity.name)
        return Session(link, identity, env, m?.let { mm -> catalog.first { it.model == mm.name } }, m?.v1 ?: (SonyMdr.V1_UUID in identity.sdpUuids && SonyMdr.V2_UUID !in identity.sdpUuids), m?.status ?: SupportStatus.EXPERIMENTAL)
    }

    private inner class Session(
        link: DeviceLink, identity: DeviceIdentity, env: DriverEnvironment,
        entry: CatalogEntry?, private val v1: Boolean, private val status: SupportStatus,
    ) : BaseDriverSession(link, identity, env, id, HeadphoneState(displayName = entry?.fullName ?: identity.name ?: identity.address, catalogEntry = entry, protocol = "Sony MDR")) {
        private var exec: CommandExecutor? = null
        private var ambientLevel = 10

        private fun cmd(name: String, kind: CommandKind, payload: ByteArray, matcher: ResponseMatcher, timeout: Long = 2000) =
            DeviceCommand("sony.$name", kind, status, payload, timeout, matcher)

        private fun isNc(p: ByteArray, wantCmd: Int) = p.size >= 2 && (p[0].toInt() and 0xFF) == wantCmd

        override suspend fun start() {
            startUniversalSources()
            if (status == SupportStatus.BASIC) return
            val uuid = if (v1) SonyMdr.V1_UUID else SonyMdr.V2_UUID
            controlOpening()
            val raw = link.openRfcomm(uuid, writable = true)
            if (raw == null) { controlFailed(); return }
            val framed = track(FramedTransport(raw, env.scope, env.clock, SonyMdr::split))
            controlOpened(framed)
            val t = track(SonyTransport(framed, env.scope) { msg, b -> log(EventCategory.RX, msg, b) })
            exec = CommandExecutor(t, SonyGuard, { env.experimentalEnabled.value }, env.sink, env.clock, link.address)
            launch {
                t.incoming.collect { p ->
                    // SonyTransport only emits frames that passed framing, escaping and checksum checks.
                    controlConfirmed()
                    log(EventCategory.RX, "MDR payload ${p.toHex()}")
                    SonyMdr.parseNc(p)?.let { s ->
                        s.ambientLevel?.let { ambientLevel = it }
                        update { it.copy(noise = NoiseStatus(s.mode, DataSource.VENDOR_PROTOCOL, env.clock())) }
                    }
                }
            }
            update {
                it.copy(
                    capabilities = DeviceCapabilities(it.capabilities.features + (Feature.NOISE_CONTROL to FeatureSupport(status, if (v1) "MDR v1 0x02" else "MDR v2 0x17"))),
                    noiseModes = listOf(NoiseMode.OFF, NoiseMode.ANC, NoiseMode.AMBIENT),
                    protocol = if (v1) "Sony MDR v1" else "Sony MDR v2",
                )
            }
            if (status != SupportStatus.EXPERIMENTAL || env.experimentalEnabled.value) {
                exec?.execute(cmd("handshake", CommandKind.READ, SonyMdr.HANDSHAKE, { (it.firstOrNull()?.toInt() ?: -1) == 0x01 }))
                refresh()
            }
        }

        override suspend fun refresh(): CommandOutcome {
            val e = exec ?: return CommandOutcome.NotConnected
            val get = if (v1) SonyMdr.ncGetV1() else SonyMdr.ncGetV2()
            return e.execute(cmd("nc.get", CommandKind.READ, get, { isNc(it, SonyMdr.NC_RET) }))
        }

        override suspend fun setNoiseMode(mode: NoiseMode): CommandOutcome {
            val e = exec ?: return CommandOutcome.NotConnected
            if (mode == NoiseMode.TALK_THRU) return CommandOutcome.Unsupported("Sony has no TalkThru mode")
            val payload = if (v1) SonyMdr.ncSetV1(mode) else SonyMdr.ncSetV2(mode, ambientLevel)
            // A GET right after a SET returns the old state; the NTFY is authoritative.
            return e.execute(cmd("nc.set.${mode.name.lowercase()}", CommandKind.WRITE, payload,
                { isNc(it, SonyMdr.NC_NTFY) && SonyMdr.parseNc(it)?.mode == mode }, timeout = 3000))
        }

        override suspend fun probe() = refresh()
    }
}
