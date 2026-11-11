package app.ewe.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.ewe.data.Session
import app.ewe.podcast.Level
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Shows a short message at the bottom of the screen. */
val LocalToast = compositionLocalOf<(String) -> Unit> { {} }

/** Runs a suspend action, tracks "busy", and shows failures instead of crashing. */
class Runner(private val scope: CoroutineScope, private val toast: (String) -> Unit) {
    var busy by mutableStateOf(false)
        private set

    fun go(done: () -> Unit = {}, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { block(); done() } catch (e: Exception) { toast(e.message ?: "Something went wrong.") } finally { busy = false }
        }
    }
}

@Composable
fun rememberRunner(): Runner {
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current
    return remember { Runner(scope, toast) }
}

// ───────────── dates ─────────────
private val dayMonth = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
private val dayMonthYear = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
private val weekDayMonth = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
private val weekCommaDayMonth = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH)

fun localDate(iso: String): LocalDate? = runCatching {
    if (iso.length <= 10) LocalDate.parse(iso) else Instant.parse(iso).atZone(ZoneId.systemDefault()).toLocalDate()
}.getOrNull() ?: runCatching { java.time.OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).toLocalDate() }.getOrNull()

/** "Today", "Yesterday", "8 Nov" */
fun relativeDay(iso: String, today: LocalDate = LocalDate.now()): String {
    val d = localDate(iso) ?: return ""
    return when (d) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> if (d.year == today.year) d.format(dayMonth) else d.format(dayMonthYear)
    }
}
fun shortDate(iso: String) = localDate(iso)?.format(dayMonth).orEmpty()
fun longDate(iso: String) = localDate(iso)?.format(dayMonthYear).orEmpty()
fun weekDate(iso: String) = localDate(iso)?.format(weekDayMonth).orEmpty()
fun weekCommaDate(d: LocalDate): String = d.format(weekCommaDayMonth)

fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"

/** **bold** markers from the assistant's chat replies. */
fun boldMarkup(text: String): AnnotatedString = buildAnnotatedString {
    text.split("**").forEachIndexed { i, part ->
        if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(part) } else append(part)
    }
}

fun highlight(text: String, query: String): AnnotatedString = buildAnnotatedString {
    append(text)
    val q = query.trim()
    if (q.isEmpty()) return@buildAnnotatedString
    var i = text.indexOf(q, ignoreCase = true)
    while (i >= 0) {
        addStyle(SpanStyle(background = C.Mark), i, i + q.length)
        i = text.indexOf(q, i + q.length, ignoreCase = true)
    }
}

// ───────────── small pieces ─────────────
@Composable
fun Tag(text: String, bg: Color, fg: Color, weight: Int = 500, size: Int = 12) {
    Text(text, style = ts(size, weight, fg), maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 8.dp, vertical = 2.dp))
}

@Composable
fun TypeTag(s: Session, size: Int = 12) =
    if (s.isTopic) Tag("Topic", C.BlueBg, C.Blue, size = size) else Tag("Project", C.PurpleBg, C.Purple, size = size)

@Composable
fun StatusTag(s: Session, size: Int = 12) =
    if (s.isActive) Tag("Active", C.GreenBg, C.Green, 600, size) else Tag("Inactive", C.RedBg, C.Red, 600, size)

@Composable
fun IconBtn(icon: ImageVector, label: String, tint: Color = C.G700, size: Dp = 44.dp, iconSize: Dp = 22.dp,
            bg: Color = Color.Transparent, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(bg)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }.alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = tint, modifier = Modifier.size(iconSize)) }
}

@Composable
fun TopBar(title: String, onBack: () -> Unit, subtitle: String? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBtn(Ic.Back, "Back", onClick = onBack)
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = ts(18, 600), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = ts(13, 400, C.G600))
        }
        trailing()
    }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, busy: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick, enabled = enabled && !busy, shape = RoundedCornerShape(14.dp),
        color = if (enabled) C.Ink else C.G400, modifier = modifier.fillMaxWidth().height(52.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = C.White, strokeWidth = 2.dp)
            else Text(text, style = ts(16, 600, C.White))
        }
    }
}

@Composable
fun OutlineButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, height: Dp = 52.dp,
                  enabled: Boolean = true, busy: Boolean = false, color: Color = C.Ink, onClick: () -> Unit) {
    Surface(
        onClick = onClick, enabled = enabled && !busy, shape = RoundedCornerShape(if (height >= 52.dp) 14.dp else 12.dp),
        color = C.White, border = BorderStroke(1.dp, C.G300), modifier = modifier.height(height).alpha(if (enabled) 1f else 0.5f),
    ) {
        Row(Modifier.padding(horizontal = 14.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = color, strokeWidth = 2.dp)
            else {
                if (icon != null) { Icon(icon, null, tint = color, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
                Text(text, style = ts(15, 600, color))
            }
        }
    }
}

/** Small bordered button used inside settings rows ("Edit", "gpt-4o ▾"). */
@Composable
fun SmallButton(text: String, enabled: Boolean = true, caret: Boolean = false, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(10.dp), color = C.White,
        border = BorderStroke(1.dp, C.G200), modifier = Modifier.heightIn(min = 36.dp)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = ts(13, 500), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (caret) Text(" ▾", style = ts(13, 500, C.G600))
        }
    }
}

@Composable
fun Caps(text: String) = Text(text.uppercase(Locale.ENGLISH), style = ts(13, 600, C.G600).copy(letterSpacing = androidx.compose.ui.unit.TextUnit(0.5f, androidx.compose.ui.unit.TextUnitType.Sp)))

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.White).border(1.dp, C.G200, RoundedCornerShape(14.dp)), content = content)
}

@Composable
fun Divider() = Box(Modifier.fillMaxWidth().height(1.dp).background(C.G100))

@Composable
fun Toggle(on: Boolean, label: String, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Box(
        Modifier.size(52.dp, 32.dp).clip(CircleShape).background(if (on) C.Green else C.G400)
            .clickable(enabled = enabled, role = Role.Switch) { onChange(!on) }
            .semantics { contentDescription = label }.padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) { Box(Modifier.size(26.dp).clip(CircleShape).background(C.White)) }
}

@Composable
fun Stepper(value: String, onMinus: () -> Unit, onPlus: () -> Unit, enabled: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        StepBtn("−", "Decrease", enabled, onMinus)
        Text(value, style = ts(15, 600), modifier = Modifier.width(72.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        StepBtn("+", "Increase", enabled, onPlus)
    }
}

@Composable
private fun StepBtn(sign: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, C.G200, RoundedCornerShape(10.dp))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center) { Text(sign, style = ts(18, 600)) }
}

/** The four-step detail ladder shown when creating a session and in its options. */
@Composable
fun Ladder(steps: List<Pair<Level, String>>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        steps.forEach { (level, days) ->
            Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(C.G100).padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(level.label, style = ts(12, 600), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(days, style = ts(11, 400, C.G600), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun TextPrompt(title: String, initial: String, confirm: String, label: String? = null, singleLine: Boolean = true,
               secret: Boolean = false, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = ts(18, 600)) },
        text = {
            OutlinedTextField(text, { text = it }, singleLine = singleLine, label = label?.let { { Text(it) } },
                textStyle = ts(16), modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None)
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text(confirm, style = ts(15, 600)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", style = ts(15, 500, C.G600)) } },
        containerColor = C.White,
    )
}

@Composable
fun Confirm(title: String, body: String, confirm: String, danger: Boolean = false, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = ts(18, 600)) },
        text = { Text(body, style = ts(15, 400, C.G600, 1.45f)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, style = ts(15, 600, if (danger) C.Red else C.Ink)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", style = ts(15, 500, C.G600)) } },
        containerColor = C.White,
    )
}
