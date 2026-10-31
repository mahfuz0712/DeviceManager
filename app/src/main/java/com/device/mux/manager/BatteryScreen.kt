package com.device.mux.manager

import android.os.BatteryManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
fun BatteryScreen() {
    val b = Monitor.batt
    Page {
        T("BATTERY", 16f, cMain, true)
        if (b == null) {
            T("READING...", 12f, cSub)
            return@Page
        }
        TwoCol(
            { MainBatteryPanel(b); PiePanel(b) },
            { DetailsPanel(b); GraphsPanel() }
        )
    }
}

private fun levelColor(b: BattSnap): Color = when {
    b.charging -> cGood
    b.level <= 15 -> cBad
    b.level <= 40 -> cWarn
    else -> cMain
}

@Composable
private fun MainBatteryPanel(b: BattSnap) {
    val col = levelColor(b)
    val border = cMain
    Panel("CHARGE STATE") {
        Box(Modifier.fillMaxWidth().height(150.dp), Alignment.Center) {
            Canvas(Modifier.width(250.dp).height(130.dp)) {
                val w = size.width
                val h = size.height
                val nub = w * 0.05f
                val bodyW = w - nub
                drawRoundRect(border, Offset(0f, 0f), Size(bodyW, h), CornerRadius(14f), style = Stroke(5f))
                drawRoundRect(border, Offset(bodyW + 3f, h * 0.3f), Size(nub - 3f, h * 0.4f), CornerRadius(6f))
                val pad = 12f
                val fillW = (bodyW - pad * 2) * (b.level.coerceIn(0, 100) / 100f)
                drawRoundRect(col.copy(alpha = 0.35f), Offset(pad, pad), Size(fillW, h - pad * 2), CornerRadius(8f))
                drawRoundRect(col, Offset(pad, pad), Size(fillW, h - pad * 2), CornerRadius(8f), style = Stroke(2f))
                // symbol on the left part of the battery: lightning when charging, down arrow when draining
                val cx = bodyW * 0.14f
                val cy = h / 2f
                val s = h * 0.30f
                if (b.charging) {
                    val bolt = Path().apply {
                        moveTo(cx + s * 0.15f, cy - s)
                        lineTo(cx - s * 0.55f, cy + s * 0.1f)
                        lineTo(cx - s * 0.05f, cy + s * 0.1f)
                        lineTo(cx - s * 0.2f, cy + s)
                        lineTo(cx + s * 0.55f, cy - s * 0.15f)
                        lineTo(cx + s * 0.05f, cy - s * 0.15f)
                        close()
                    }
                    drawPath(bolt, Color.White)
                } else {
                    val arrow = Path().apply {
                        moveTo(cx - s * 0.2f, cy - s)
                        lineTo(cx + s * 0.2f, cy - s)
                        lineTo(cx + s * 0.2f, cy)
                        lineTo(cx + s * 0.6f, cy)
                        lineTo(cx, cy + s)
                        lineTo(cx - s * 0.6f, cy)
                        lineTo(cx - s * 0.2f, cy)
                        close()
                    }
                    drawPath(arrow, Color.White)
                }
            }
            T("${b.level}%", 40f, Color.White, true, Modifier.padding(start = 40.dp))
        }
        val state = when {
            !b.present -> "NO BATTERY"
            b.full -> "FULL"
            b.charging -> "CHARGING"
            else -> "NOT CHARGING (DRAINING)"
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            T(state, 14f, if (b.charging) cGood else cWarn, true)
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.Center) {
            T(if (b.charging) "▲ ENERGY IN" else "▼ ENERGY OUT", 11f, cSub)
        }
    }
}

@Composable
private fun PiePanel(b: BattSnap) {
    Panel("CHARGE PIE") {
        PieWithLegend(
            listOf(
                Slice("CHARGED", b.level.toFloat(), levelColor(b), "${b.level}%"),
                Slice("EMPTY", (100 - b.level).toFloat(), cDim, "${100 - b.level}%")
            ),
            "${b.level}%"
        )
    }
}

private fun healthText(h: Int): String = when (h) {
    BatteryManager.BATTERY_HEALTH_GOOD -> "GOOD"
    BatteryManager.BATTERY_HEALTH_OVERHEAT -> "OVERHEAT"
    BatteryManager.BATTERY_HEALTH_DEAD -> "DEAD"
    BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "OVER VOLTAGE"
    BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "FAILURE"
    BatteryManager.BATTERY_HEALTH_COLD -> "COLD"
    else -> "UNKNOWN"
}

private fun sourceText(p: Int): String = when (p) {
    BatteryManager.BATTERY_PLUGGED_AC -> "AC ADAPTER"
    BatteryManager.BATTERY_PLUGGED_USB -> "USB"
    BatteryManager.BATTERY_PLUGGED_WIRELESS -> "WIRELESS"
    0 -> "NONE (ON BATTERY)"
    else -> "OTHER"
}

@Composable
private fun DetailsPanel(b: BattSnap) {
    val charging = b.charging
    val watts = b.voltageV * b.currentMa / 1000f
    Panel("BATTERY DETAILS") {
        KV("LEVEL", "${b.level}%")
        KV("STATUS", if (b.full) "FULL" else if (charging) "CHARGING" else "NOT CHARGING", if (charging) cGood else cWarn)
        KV("POWER SOURCE", sourceText(b.plugged))
        KV("HEALTH", healthText(b.health), if (b.health == BatteryManager.BATTERY_HEALTH_GOOD) cGood else cBad)
        KV("TEMPERATURE", "${f1(b.tempC)} °C / ${f1(b.tempC * 9f / 5f + 32f)} °F", tempColor(b.tempC))
        KV("VOLTAGE", "${f2(b.voltageV)} V")
        KV(if (charging) "CHARGING CURRENT" else "DISCHARGE CURRENT", if (b.currentMa > 0f) "${f0(b.currentMa)} mA (${f2(b.currentMa / 1000f)} A)" else "N/A")
        KV("POWER", if (b.currentMa > 0f) "${f2(watts)} W" else "N/A")
        KV("TECHNOLOGY", b.tech.ifEmpty { "N/A" })
        if (b.chargeMah > 0f) KV("CHARGE NOW", "${f0(b.chargeMah)} mAh")
        if (b.chargeMah > 0f && b.level in 5..100) KV("EST. FULL CAPACITY", "${f0(b.chargeMah * 100f / b.level)} mAh")
        if (Monitor.designMah > 0f) {
            KV("DESIGN CAPACITY", "${f0(Monitor.designMah)} mAh")
            if (b.chargeMah > 0f && b.level in 5..100) {
                val hp = (b.chargeMah * 100f / b.level) / Monitor.designMah
                KV("CAPACITY HEALTH", "${f0(hp.coerceAtMost(1.2f) * 100f)}%")
            }
        }
        if (b.cycles > 0) KV("CYCLE COUNT", "${b.cycles}")
        if (b.remainMs > 0) KV(if (charging) "TIME TO FULL" else "TIME REMAINING (EST.)", fmtDuration(b.remainMs))
    }
}

@Composable
private fun GraphsPanel() {
    Panel("LIVE GRAPHS (THIS SESSION)") {
        T("LEVEL %", 10f, cSub)
        LineGraph(listOf(Series(Monitor.battLevelHist, cGood)), 0f, 100f, "%")
        T("TEMPERATURE °C", 10f, cSub, mod = Modifier.padding(top = 8.dp))
        LineGraph(listOf(Series(Monitor.battTempHist, cWarn)), unit = "°")
        T("VOLTAGE V", 10f, cSub, mod = Modifier.padding(top = 8.dp))
        LineGraph(listOf(Series(Monitor.battVoltHist, cMain)), unit = "V")
        T("CURRENT mA (+ in, - out)", 10f, cSub, mod = Modifier.padding(top = 8.dp))
        LineGraph(listOf(Series(Monitor.battCurHist, cBad)), unit = "mA")
        T("POWER W (+ charging, - draining)", 10f, cSub, mod = Modifier.padding(top = 8.dp))
        LineGraph(listOf(Series(Monitor.battPowerHist, Color(0xFFB388FF))), unit = "W")
    }
}
