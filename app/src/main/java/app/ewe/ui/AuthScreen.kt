package app.ewe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.ewe.data.Repository

/** First run: point the app at your Supabase project and sign in. */
@Composable
fun AuthScreen(repo: Repository) {
    val run = rememberRunner()
    val toast = LocalToast.current
    var url by rememberSaveable { mutableStateOf(repo.prefs.supabaseUrl) }
    var key by rememberSaveable { mutableStateOf(repo.prefs.anonKey) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var create by rememberSaveable { mutableStateOf(false) }
    var showProject by rememberSaveable { mutableStateOf(url.isBlank() || key.isBlank()) }
    val ready = url.isNotBlank() && key.isNotBlank() && email.contains('@') && password.length >= 6

    Column(
        Modifier.fillMaxSize().background(C.G50).verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("ewe", style = ts(34, 700).copy(letterSpacing = androidx.compose.ui.unit.TextUnit(-0.6f, androidx.compose.ui.unit.TextUnitType.Sp)))
        Text("Short notes from what you read, brought back for revision.", style = ts(15, 400, C.G600, 1.45f))
        Spacer(Modifier.height(4.dp))

        if (showProject) {
            Caps("Your Supabase project")
            Field(url, { url = it }, "Project URL", "https://xxxx.supabase.co", KeyboardType.Uri)
            Field(key, { key = it }, "Anon / publishable key", "eyJ… or sb_publishable_…")
            Text("Both are in Supabase → Project settings → API. Your notes are stored in this project; a copy stays on the phone for offline reading.",
                style = ts(13, 400, C.G600, 1.45f))
        } else {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.G100).clickable { showProject = true }.padding(12.dp)) {
                Text(url.removePrefix("https://"), style = ts(13, 500, C.G700), modifier = Modifier.weight(1f), maxLines = 1)
                Text("Change", style = ts(13, 600, C.Blue))
            }
        }

        Caps(if (create) "Create account" else "Sign in")
        Field(email, { email = it }, "Email", "you@example.com", KeyboardType.Email)
        Field(password, { password = it }, "Password", "At least 6 characters", KeyboardType.Password, secret = true)

        PrimaryButton(if (create) "Create account" else "Sign in", enabled = ready, busy = run.busy) {
            run.go { repo.signIn(url, key, email.trim(), password, create)?.let { toast(it); create = false } }
        }
        Text(if (create) "Already have an account? Sign in" else "New here? Create an account",
            style = ts(14, 600, C.Blue), modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { create = !create }.padding(8.dp))
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String, hint: String,
                  type: KeyboardType = KeyboardType.Text, secret: Boolean = false) {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, placeholder = { Text(hint, style = ts(15, 400, C.G400)) }, singleLine = true,
        textStyle = ts(16), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(keyboardType = type, autoCorrectEnabled = false),
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
    )
}
