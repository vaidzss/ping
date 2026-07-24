package dev.meshaid.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------------------- auth screens
// Same field-radio instrument-panel language as the mesh screen (dark, monospace, one
// accent per meaning) — this is the one screen with no radio traffic behind it yet, so it
// leans on the wordmark and a plain-language explanation of what "password" means here:
// there is no server account, only a local key the password unlocks.

@Composable
internal fun SignUpScreen(error: String?, onSubmit: (username: String, password: String) -> Unit) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var revealed by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }

    fun submit() {
        localError = when {
            username.trim().isEmpty() -> "Choose a callsign."
            password.length < 8 -> "Password must be at least 8 characters."
            password != confirm -> "Passwords don't match."
            else -> null
        }
        if (localError == null) onSubmit(username.trim(), password)
    }

    AuthScaffold(
        headline = "NEW IDENTITY",
        blurb = "Your callsign and password never leave this phone — there is no account " +
            "server. The password encrypts your mesh identity key on-device; if you lose it, " +
            "there is no recovery, only a new identity.",
    ) {
        AuthField(
            value = username,
            onChange = { username = it },
            label = "CALLSIGN",
            imeAction = ImeAction.Next,
        )
        Spacer(Modifier.height(12.dp))
        AuthField(
            value = password,
            onChange = { password = it },
            label = "PASSWORD",
            secret = !revealed,
            imeAction = ImeAction.Next,
        )
        Spacer(Modifier.height(12.dp))
        AuthField(
            value = confirm,
            onChange = { confirm = it },
            label = "CONFIRM PASSWORD",
            secret = !revealed,
            imeAction = ImeAction.Done,
            onDone = ::submit,
        )
        Spacer(Modifier.height(8.dp))
        RevealToggle(revealed) { revealed = it }
        (localError ?: error)?.let { AuthError(it) }
        Spacer(Modifier.height(18.dp))
        AuthButton("CREATE IDENTITY", onClick = ::submit)
    }
}

@Composable
internal fun LoginScreen(username: String, error: String?, onSubmit: (password: String) -> Unit) {
    var password by remember { mutableStateOf("") }
    var revealed by remember { mutableStateOf(false) }

    fun submit() {
        if (password.isNotEmpty()) onSubmit(password)
    }

    AuthScaffold(
        headline = "WELCOME BACK",
        blurb = "Enter the password for \"${username.uppercase()}\" to unlock your mesh identity on this device.",
    ) {
        AuthField(
            value = password,
            onChange = { password = it },
            label = "PASSWORD",
            secret = !revealed,
            imeAction = ImeAction.Done,
            onDone = ::submit,
        )
        Spacer(Modifier.height(8.dp))
        RevealToggle(revealed) { revealed = it }
        error?.let { AuthError(it) }
        Spacer(Modifier.height(18.dp))
        AuthButton("UNLOCK", onClick = ::submit)
    }
}

@Composable
private fun AuthScaffold(headline: String, blurb: String, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(Night).statusBarsPadding().navigationBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp),
        ) {
            Text(
                "PING",
                color = Chalk,
                fontFamily = Mono,
                fontWeight = FontWeight.Black,
                fontSize = 28.sp,
                letterSpacing = 6.sp,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "OFFLINE EMERGENCY MESH",
                color = MeshGreen,
                fontFamily = Mono,
                fontSize = 11.sp,
                letterSpacing = 3.sp,
            )
            Spacer(Modifier.height(28.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Panel, RoundedCornerShape(10.dp))
                    .padding(20.dp),
            ) {
                Column {
                    Text(
                        headline,
                        color = Chalk,
                        fontFamily = Mono,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        letterSpacing = 2.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        blurb,
                        color = Slate,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    )
                    Spacer(Modifier.height(20.dp))
                    content()
                }
            }
        }
    }
}

@Composable
private fun AuthField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    imeAction: ImeAction,
    secret: Boolean = false,
    onDone: (() -> Unit)? = null,
) {
    Column {
        Text(label, color = Slate, fontFamily = Mono, fontSize = 11.sp, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Chalk,
                unfocusedTextColor = Chalk,
                focusedContainerColor = Night,
                unfocusedContainerColor = Night,
                focusedBorderColor = MeshGreen.copy(alpha = 0.6f),
                unfocusedBorderColor = Inkwell,
                cursorColor = MeshGreen,
            ),
            shape = RoundedCornerShape(6.dp),
            keyboardOptions = KeyboardOptions(
                imeAction = imeAction,
                keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text,
            ),
            keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        )
    }
}

@Composable
private fun RevealToggle(revealed: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.clickable { onChange(!revealed) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .height(14.dp)
                .padding(end = 8.dp),
        ) {
            Text(
                if (revealed) "[x]" else "[ ]",
                color = if (revealed) MeshGreen else Slate,
                fontFamily = Mono,
                fontSize = 13.sp,
            )
        }
        Text("SHOW PASSWORD", color = Slate, fontFamily = Mono, fontSize = 11.sp, letterSpacing = 1.sp)
    }
}

@Composable
private fun AuthError(message: String) {
    Spacer(Modifier.height(10.dp))
    Text(
        message,
        color = SystemAmber,
        fontFamily = Mono,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    )
}

@Composable
private fun AuthButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MeshGreen, contentColor = Night),
        shape = RoundedCornerShape(6.dp),
        contentPadding = PaddingValues(0.dp),
    ) {
        Text(label, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 2.sp)
    }
}
