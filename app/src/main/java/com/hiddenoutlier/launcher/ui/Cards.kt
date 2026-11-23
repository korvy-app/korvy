package com.hiddenoutlier.launcher.ui

import android.Manifest
import android.content.Intent
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.hiddenoutlier.launcher.assistant.AssistantController
import com.hiddenoutlier.launcher.assistant.Card

/** What the buttons on a confirmation card do. Needs an Activity (permission dialog, contact picker). */
class CardActions(
    val confirm: (Long, String?) -> Unit,
    val cancel: (Long) -> Unit,
    val allowContacts: (Long) -> Unit,
    val pickContact: (Long) -> Unit,
)

@Composable
fun rememberCardActions(assistant: AssistantController): CardActions {
    var pendingId by remember { mutableLongStateOf(-1L) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && pendingId >= 0) assistant.retry(pendingId)
    }
    // Android's own contact picker: the app gets just the chosen contact, no permission needed.
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
        )
    }
}

/** The card under an assistant reply: confirm what will be sent/called/booked, or say who. */
@Composable
fun ActionCard(id: Long, card: Card, actions: CardActions) {
    // Keyed on the card too: a "who?" card is replaced by a confirm card with the message in the same chat item.
    var message by remember(id, card) { mutableStateOf(card.message.orEmpty()) }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).testTag("card_$id")) {
        Text(card.title, style = MaterialTheme.typography.titleSmall)
        Text(card.detail, style = MaterialTheme.typography.bodySmall)
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
            Card.Kind.NEED_CONTACT -> androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { actions.cancel(id) }) { Text("Cancel") }
                OutlinedButton(onClick = { actions.pickContact(id) }, modifier = Modifier.testTag("card_pick")) { Text("Pick contact") }
                Button(onClick = { actions.allowContacts(id) }, modifier = Modifier.testTag("card_allow")) { Text("Allow contacts") }
            }
        }
    }
}
