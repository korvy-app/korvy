package app.korvy.launcher.ui

import android.Manifest
import android.content.Intent
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import app.korvy.launcher.assistant.contactPhoto
import app.korvy.launcher.assistant.AssistantController
import app.korvy.launcher.assistant.Card
import app.korvy.launcher.assistant.Person

/** What the buttons on a confirmation card do. Needs an Activity (permission dialog, contact picker). */
class CardActions(
    val confirm: (Long, String?) -> Unit,
    val cancel: (Long) -> Unit,
    val allowContacts: (Long) -> Unit,
    val pickContact: (Long) -> Unit,
    val allowLocation: (Long) -> Unit = {},
    val choose: (Long, Person, Boolean) -> Unit = { _, _, _ -> },
)

@Composable
fun rememberCardActions(assistant: AssistantController): CardActions {
    var pendingId by remember { mutableLongStateOf(-1L) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && pendingId >= 0) assistant.retry(pendingId)
    }
    // Android's own contact picker: the app gets just the chosen contact, no permission needed.
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && pendingId >= 0) assistant.retry(pendingId)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val uri = r.data?.data ?: return@rememberLauncherForActivityResult
        assistant.contacts.fromPicked(uri)?.let { if (pendingId >= 0) assistant.withContact(pendingId, it) }
    }
    return remember(assistant) {
        CardActions(
            confirm = { id, m -> assistant.confirm(id, m) },
            cancel = { id -> assistant.cancel(id) },
            allowContacts = { id -> pendingId = id; permission.launch(Manifest.permission.READ_CONTACTS) },
            pickContact = { id -> pendingId = id; picker.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)) },
            allowLocation = { id -> pendingId = id; locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION) },
            choose = { id, p, remember -> assistant.choose(id, p, remember) },
        )
    }
}

/** The card under an assistant reply: confirm what will be sent/called/booked, or say who. */
@Composable
fun ActionCard(id: Long, card: Card, actions: CardActions) {
    // Keyed on the card too: a "who?" card is replaced by a confirm card with the message in the same chat item.
    var message by remember(id, card) { mutableStateOf(card.message.orEmpty()) }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).testTag("card_$id")) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            card.person?.let { ContactAvatar(it, 40.dp); androidx.compose.foundation.layout.Spacer(Modifier.padding(start = 10.dp)) }
            Column {
                Text(card.title, style = MaterialTheme.typography.titleSmall)
                Text(card.detail, style = MaterialTheme.typography.bodySmall)
            }
        }
        when (card.kind) {
            Card.Kind.CONFIRM -> {
                if (card.message != null) OutlinedTextField(
                    value = message, onValueChange = { message = it }, label = { Text("Message") },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag("card_message"), maxLines = 4,
                )
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { actions.cancel(id) }, modifier = Modifier.testTag("card_cancel")) { Text("Cancel") }
                    Button(onClick = { actions.confirm(id, if (card.message != null) message else null) },
                        modifier = Modifier.testTag("card_confirm")) { Text(card.confirmLabel) }
                }
            }
            Card.Kind.CHOOSE_CONTACT -> {
                var rememberChoice by remember(id, card) { mutableStateOf(true) }
                card.options.forEach { p ->
                    Row(Modifier.fillMaxWidth().clickable { actions.choose(id, p, rememberChoice) }.padding(vertical = 6.dp).testTag("choose_${p.name}"),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        ContactAvatar(p, 36.dp)
                        Column(Modifier.padding(start = 10.dp)) {
                            Text(p.name, style = MaterialTheme.typography.bodyLarge)
                            Text(listOf(p.label, p.number).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().clickable { rememberChoice = !rememberChoice }, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(checked = rememberChoice, onCheckedChange = { rememberChoice = it }, modifier = Modifier.testTag("choose_remember"))
                    Text("Always use this person for “${card.alias}”", style = MaterialTheme.typography.bodySmall)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { actions.cancel(id) }) { Text("Cancel") }
                }
            }
            Card.Kind.NEED_LOCATION -> Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { actions.cancel(id) }) { Text("Cancel") }
                Button(onClick = { actions.allowLocation(id) }, modifier = Modifier.testTag("card_allow_location")) { Text("Allow location") }
            }
            Card.Kind.NEED_CONTACT -> androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { actions.cancel(id) }) { Text("Cancel") }
                OutlinedButton(onClick = { actions.pickContact(id) }, modifier = Modifier.testTag("card_pick")) { Text("Pick contact") }
                Button(onClick = { actions.allowContacts(id) }, modifier = Modifier.testTag("card_allow")) { Text("Allow contacts") }
            }
        }
    }
}

/** A contact's photo, or their initial on a coloured circle. Loaded off the main thread. */
@Composable
fun ContactAvatar(p: Person, size: androidx.compose.ui.unit.Dp) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val bmp by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(null, p.photo) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            context.contactPhotoOrNull(p.photo)
        }
    }
    val shape = androidx.compose.foundation.shape.CircleShape
    androidx.compose.foundation.layout.Box(
        Modifier.size(size).clip(shape)
            .background(MaterialTheme.colorScheme.primaryContainer, shape),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        val b = bmp
        if (b != null) androidx.compose.foundation.Image(b.asImageBitmap(), null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.size(size))
        else Text(p.name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?", color = MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.typography.titleMedium)
    }
}

private fun android.content.Context.contactPhotoOrNull(uri: String?) = with(this) { contactPhoto(uri) }
