package de.mlampert.batterymanager.monitor.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Engine-Kern (spec/engine/spec.md §5–7): Ladesitzung, Ziel-Alarm, Pause/Defizit, Segmentbrüche,
 * Uhrsprünge, Hitze und Restzeit. Reines Kotlin ohne Android – getestet mit den Golden-Fixtures.
 */
class Engine(
    var profile: Profile = Profile(),
    var settings: Settings = Settings(),
    /** Gelernte Ladekurve: (plug, klass, pct) → Sekunden je Prozentpunkt. */
    var chargeProfile: Map<Triple<Int, Int, Int>, Double> = emptyMap(),
    /** Geschätzte Akkukapazität (mAh, aus CHARGE_COUNTER), für die Sofort-Restzeit vor dem ersten %-Schritt. */
    var capacityMah: Double? = null,
    /** Erkanntes Ladelimit des Geräts in % (z. B. Ladeschutz 90 %), null = unbekannt. */
    var deviceLimitPct: Int? = null,
) {
    class Session(
        val startT: Long,
        val startUtc: Long,
        val startLevelPm: Int,
        val plug: Int,
        val startKnown: Boolean,
    ) {
        var dbId: Long = 0
        var reached = false
        var reachedUtc: Long? = null
        var alarmMuted = false
        var klass = 0
        var lastLevelPm = startLevelPm
        var lastPct = startLevelPm / 10
        var lastStepT: Long? = null
        var liveSecPerPct: Double? = null
        var steps = 0
        val stepSec = mutableMapOf<Int, Double>()
        var levelConstSince = startT
        var pause = false
        var deficit = false
        var negCount = 0
        var fallSteps = 0
        var warmupSum = 0.0
        var warmupN = 0
        var eRaw = 0.0
        var pkMaxRaw: Double? = null
        var pkMinRaw: Double? = null
        var lastT = startT
        var lastUtc = startUtc
        var fullSinceUtc: Long? = null
        var powerEmaMw: Double? = null
        var maxPowerMw: Long? = null
        var etaShownTarget: Long? = null
        var etaShownFull: Long? = null
        /** Ladepause/Halten: seit wann und auf welchem Stand (für die Ladelimit-Erkennung). */
        var pauseSinceT: Long? = null
        var pausePct: Int? = null
        var limitRecorded = false
        /** Energie und Dauer nur der aktiven Ladephase (ohne Halten/Pause/Defizit) – für den Ø. */
        var activeERaw = 0.0
        var activeMs = 0L
        /** Anteil seit dem letzten Stand-Wechsel – wird bei erkannter Pause rückwirkend abgezogen. */
        var constERaw = 0.0
        var constMs = 0L
        /** Letzte drei Leistungswerte; Spitze = Maximum des gleitenden Medians (filtert Messspitzen). */
        val lastPowers = ArrayDeque<Long>()
        /** Ø-Leistung der aktiven Ladephase in mW (normalisiert mit dem übergebenen Profil). */
        fun activeAvgMw(p: Profile): Long? =
            if (activeMs < 60_000 || activeERaw <= 0) null
            else roundHalfAway(p.sign * p.f * activeERaw / (activeMs / 1000.0))
    }

    var session: Session? = null
        private set
    var last: Reading? = null
        private set
    var heat = false
        private set
    private var unplugSince: Long? = null

    /** Letzte abgeschlossene Sitzung (für Zusammenfassung und Lernen). */
    var lastEnded: Session? = null
        private set

    /** Zustand nach Dienst-Neustart wiederherstellen (letzter Messpunkt + offene Sitzung). */
    fun restore(lastReading: Reading?, open: Session?) {
        last = lastReading
        session = open
    }

    fun onReading(r: Reading): List<Event> {
        val ev = mutableListOf<Event>()
        val prev = last
        var handled = false

        if (prev != null && (r.span != prev.span || r.boot != prev.boot)) {
            val s = session
            if (s != null) {
                val sameBoot = r.boot == prev.boot
                val gap = r.t - prev.t
                if (sameBoot && gap in 0 until RESUME_GAP_MS && r.plugged) {
                    ev += Event(Event.SESSION_RESUME)
                } else {
                    endSession(prev.t, prev.utc, if (sameBoot) "kill" else "reboot", ev)
                    if (r.plugged) {
                        start(r, startKnown = false, outage = !s.reached, ev = ev)
                        handled = true
                    }
                }
            }
        } else if (prev != null) {
            val dt = r.t - prev.t
            val drift = (r.utc - prev.utc) - dt
            if (abs(drift) > CLOCK_JUMP_MS) ev += Event(Event.CLOCK_JUMP, mapOf("deltaMs" to drift))
            if (dt > 0) {
                val dPct = abs(r.levelPm - prev.levelPm) / 10.0
                val perMin = dPct / (dt / 60_000.0)
                val limit = if (r.plugged) 6.0 else 2.0
                if (dPct >= 3.0 && perMin > limit) ev += Event(Event.SEGMENT_BREAK, mapOf("reason" to "jump"))
            }
        }

        if (!handled) {
            val s = session
            if (s == null) {
                if (r.plugged) start(r, startKnown = true, outage = false, ev = ev)
            } else if (!r.plugged) {
                val since = unplugSince ?: r.t.also { unplugSince = it }
                if (r.t - since >= UNPLUG_END_MS) endSession(since, r.utc - (r.t - since), "unplug", ev)
            } else {
                unplugSince = null
                update(s, r, ev)
            }
        }

        val t = profile.tempDc(r.tempDc)
        if (t != null) {
            if (!heat && t >= settings.heatWarnDc) { heat = true; ev += Event(Event.HEAT_WARN, mapOf("tempDc" to t)) }
            else if (heat && t < settings.heatWarnDc - 30) { heat = false; ev += Event(Event.HEAT_CLEAR) }
        }
        last = r
        return ev
    }

    /** Vom Dienst nach ≥ 120 s ohne neue Messung aufgerufen: beendet eine abgesteckte Sitzung. */
    fun checkUnplugged(now: Long, nowUtc: Long): List<Event> {
        val since = unplugSince ?: return emptyList()
        if (session == null || now - since < UNPLUG_END_MS) return emptyList()
        val ev = mutableListOf<Event>()
        endSession(since, nowUtc - (now - since), "unplug", ev)
        return ev
    }

    fun muteAlarm() { session?.alarmMuted = true }

    private fun start(r: Reading, startKnown: Boolean, outage: Boolean, ev: MutableList<Event>) {
        val s = Session(r.t, r.utc, r.levelPm, r.plug, startKnown)
        session = s
        unplugSince = null
        ev += Event(Event.SESSION_START, mapOf("startKnown" to startKnown))
        if (r.pct >= settings.targetPct) {
            s.reached = true
            s.reachedUtc = r.utc
            ev += if (outage && !startKnown) {
                Event(Event.TARGET_EXCEEDED_DURING_OUTAGE, mapOf("levelPct" to r.pct))
            } else {
                Event(Event.TARGET_ALREADY_REACHED)
            }
        }
        update(s, r, ev, first = true)
    }

    private fun endSession(endT: Long, endUtc: Long, reason: String, ev: MutableList<Event>) {
        val s = session ?: return
        ev += Event(Event.SESSION_END, mapOf("reason" to reason, "endT" to endT, "endUtc" to endUtc))
        lastEnded = s
        session = null
        unplugSince = null
    }

    private fun update(s: Session, r: Reading, ev: MutableList<Event>, first: Boolean = false) {
        val c = profile.currentMa(r.currentRaw)
        val p = profile.powerMw(r.currentRaw, r.voltageRaw, r.powerRaw)

        // Warmup und Leistungsklasse
        val sinceStart = r.t - s.startT
        // Leistungsklasse: sofort vorläufig aus dem laufenden Mittel, nach dem Warmup fest.
        if (sinceStart < WARMUP_MS && p != null && p > 0) {
            s.warmupSum += p; s.warmupN++
            val avg = s.warmupSum / s.warmupN
            s.klass = when { avg <= 7_500 -> 1; avg <= 18_000 -> 2; else -> 3 }
        }
        if (p != null) {
            s.powerEmaMw = s.powerEmaMw?.let { it + 0.3 * (p - it) } ?: p.toDouble()
            s.lastPowers.addLast(p)
            if (s.lastPowers.size > 3) s.lastPowers.removeFirst()
            if (s.lastPowers.size == 3) {
                val median = s.lastPowers.sorted()[1]
                s.maxPowerMw = max(s.maxPowerMw ?: median, median)
            }
        }

        // Energie (Rohprodukt × s) für session_n
        if (!first) {
            val dtS = (r.t - s.lastT) / 1000.0
            val raw = rawProduct(r)
            if (raw != null && dtS in 0.0..600.0) {
                s.eRaw += raw * dtS
                if (!s.pause && !s.deficit && s.fullSinceUtc == null) {
                    s.activeERaw += raw * dtS
                    s.activeMs += r.t - s.lastT
                    if (r.levelPm == s.lastLevelPm) {
                        s.constERaw += raw * dtS
                        s.constMs += r.t - s.lastT
                    } else {
                        s.constERaw = 0.0
                        s.constMs = 0
                    }
                }
                s.pkMaxRaw = max(s.pkMaxRaw ?: raw, raw)
                s.pkMinRaw = min(s.pkMinRaw ?: raw, raw)
            }
        }

        // Prozentschritte → Live-Rate
        val pct = r.pct
        if (pct > s.lastPct) {
            val lst = s.lastStepT
            if (lst != null && !s.pause) {
                val sec = (r.t - lst) / 1000.0 / (pct - s.lastPct)
                for (q in s.lastPct until pct) s.stepSec[q] = sec
                s.liveSecPerPct = s.liveSecPerPct?.let { it + 0.3 * (sec - it) } ?: sec
                s.steps++
            }
            s.lastStepT = r.t
            s.lastPct = pct
        } else if (pct < s.lastPct) {
            s.lastPct = pct
            s.lastStepT = null
        }

        // Pause
        if (r.levelPm != s.lastLevelPm) s.levelConstSince = r.t
        // Pause/Halten: Ladestand ≥ 10 min konstant und kaum Strom (Handys „halten“ am Limit oft mit
        // 50–300 mA Erhaltungsstrom). Ohne Stromwert genügt der konstante Ladestand.
        if (!s.pause && (c == null || abs(c) < HOLD_MA) && r.t - s.levelConstSince >= PAUSE_MS) {
            s.pause = true; s.pauseSinceT = s.levelConstSince; s.pausePct = r.pct
            // Die Haltezeit vor der Erkennung gehört nicht zur aktiven Ladephase.
            s.activeERaw -= s.constERaw; s.activeMs -= s.constMs
            s.constERaw = 0.0; s.constMs = 0
            ev += Event(Event.PAUSE_START, mapOf("levelPct" to r.pct))
        } else if (s.pause && (r.levelPm != s.lastLevelPm || (c != null && abs(c) >= HOLD_MA))) {
            s.pause = false; s.pauseSinceT = null; s.pausePct = null
            ev += Event(Event.PAUSE_END)
        }

        // Defizit (eingesteckt, aber Netto-Entladung)
        s.negCount = if (c != null && c < 0) s.negCount + 1 else 0
        if (r.levelPm < s.lastLevelPm) s.fallSteps++
        if (!s.deficit && (s.negCount >= 3 || s.fallSteps >= 2)) {
            s.deficit = true; ev += Event(Event.DEFICIT_START)
        } else if (s.deficit && c != null && c > 0 && r.levelPm >= s.lastLevelPm) {
            s.deficit = false; s.fallSteps = 0; ev += Event(Event.DEFICIT_END)
        }

        // Ziel-Alarm mit 2-%-Hysterese
        val limit = deviceLimitPct
        if (!first && !s.reached && limit != null && settings.targetPct >= limit && s.pause && pct >= limit - 2) {
            // Ziel liegt am/über dem Gerätelimit: Das Gerät hält jetzt an → das ist „Ziel erreicht“.
            s.reached = true; s.reachedUtc = r.utc
            ev += Event(Event.TARGET_REACHED, mapOf("targetPct" to settings.targetPct, "deviceLimit" to limit))
        } else if (!first) {
            if (!s.reached && pct >= settings.targetPct) {
                s.reached = true; s.reachedUtc = r.utc
                ev += Event(Event.TARGET_REACHED, mapOf("targetPct" to settings.targetPct))
            } else if (s.reached && pct < effectiveTarget(limit) - 2) {
                s.reached = false
            }
        }

        if (r.levelPm >= 1000 || r.status == STATUS_FULL) { if (s.fullSinceUtc == null) s.fullSinceUtc = r.utc }
        else s.fullSinceUtc = null

        s.lastLevelPm = r.levelPm
        s.lastT = r.t
        s.lastUtc = r.utc
    }

    /** Ziel, das tatsächlich erreichbar ist: liegt es am/über dem Gerätelimit, gilt der Haltepunkt (Limit − 2). */
    private fun effectiveTarget(limit: Int?): Int =
        if (limit != null && settings.targetPct >= limit) limit - 2 else settings.targetPct

    private fun rawProduct(r: Reading): Double? = when {
        profile.pSrc == 1 -> r.powerRaw?.toDouble()
        r.currentRaw == null || r.voltageRaw == null -> null
        else -> r.currentRaw.toDouble() * r.voltageRaw.toDouble()
    }

    /** Restzeit gemäß spec §7. */
    fun eta(): Eta {
        val s = session ?: return Eta(EtaState.IDLE)
        val r = last ?: return Eta(EtaState.IDLE)
        if (s.fullSinceUtc != null) return Eta(EtaState.FULL)
        if (s.pause) return Eta(EtaState.PAUSE)
        if (s.deficit) return Eta(EtaState.DEFICIT)

        // Vor dem ersten beobachteten %-Schritt: Rate aus Ladestrom und Kapazität schätzen (nur „ca.“).
        val fromCurrent = run {
            val c = profile.currentMa(r.currentRaw) ?: return@run null
            val cap = capacityMah ?: return@run null
            if (c < 100) null else cap * 0.01 / c * 3600.0
        }
        val live = s.liveSecPerPct ?: fromCurrent
        val pct = r.pct
        fun rate(p: Int): Double? {
            val learned = chargeProfile[Triple(s.plug, s.klass, p)]
            if (learned != null) {
                val ref = chargeProfile[Triple(s.plug, s.klass, max(0, pct - 1))]
                val k = if (live != null && ref != null && ref > 0) (live / ref).coerceIn(0.5, 2.0) else 1.0
                return learned * k
            }
            live ?: return null
            val cv = if (p < 80) 1.0 else 1.0 + 2.0 * (p - 80) / 20.0
            val liveCv = if (pct < 80) 1.0 else 1.0 + 2.0 * (pct - 80) / 20.0
            return live / liveCv * cv
        }
        fun remaining(to: Int): Long? {
            if (pct >= to) return 0
            var sum = 0.0
            for (p in pct until to) sum += rate(p) ?: return null
            return sum.toLong()
        }
        val top = deviceLimitPct?.coerceIn(50, 100) ?: 100
        val target = remaining(minOf(settings.targetPct, top))
        val full = remaining(top)
        // Ohne jede Ratenquelle: im Warmup „wird berechnet“, danach „—“.
        if (target == null && full == null) {
            return Eta(if (r.t - s.startT < WARMUP_MS) EtaState.COMPUTING else EtaState.NONE)
        }

        val covered = (pct until 100).count { chargeProfile.containsKey(Triple(s.plug, s.klass, it)) }
        val coverage = if (pct >= 100) 1.0 else covered.toDouble() / (100 - pct)
        var conf = when {
            s.steps >= 3 && coverage >= 0.8 -> 0.8
            s.steps >= 3 || coverage >= 0.8 -> 0.5
            else -> 0.2
        }
        if (s.liveSecPerPct == null && fromCurrent != null) conf = max(conf, 0.45)
        val estimated = !profile.currentSupported
        if (estimated) conf = min(conf, 0.5)
        val state = when { conf >= 0.7 -> EtaState.OK; conf >= 0.4 -> EtaState.APPROX; else -> EtaState.NONE }
        if (state == EtaState.NONE) {
            return Eta(if (r.t - s.startT < WARMUP_MS) EtaState.COMPUTING else EtaState.NONE, conf = conf, estimated = estimated)
        }
        s.etaShownTarget = hysteresis(s.etaShownTarget, target)
        s.etaShownFull = hysteresis(s.etaShownFull, full)
        return Eta(state, s.etaShownTarget, s.etaShownFull, conf, estimated)
    }

    private fun hysteresis(shown: Long?, now: Long?): Long? {
        if (now == null || shown == null) return now
        return if (abs(now - shown) > max(60.0, shown * 0.1)) now else shown
    }

    companion object {
        const val WARMUP_MS = 90_000L
        const val UNPLUG_END_MS = 120_000L
        const val RESUME_GAP_MS = 5 * 60_000L
        const val CLOCK_JUMP_MS = 120_000L
        const val PAUSE_MS = 10 * 60_000L
        const val HOLD_MA = 300L
        const val STATUS_FULL = 5
    }
}
