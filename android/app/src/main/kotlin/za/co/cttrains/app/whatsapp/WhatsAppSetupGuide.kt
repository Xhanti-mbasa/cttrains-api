@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package za.co.cttrains.app.whatsapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Bundled instructions remain readable offline; connection details are never put in this guide. */
@Composable internal fun WhatsAppSetupGuide(onDismiss: ()->Unit) {
    val uri=LocalUriHandler.current
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        LazyColumn(Modifier.fillMaxWidth().testTag("whatsapp-setup-guide"),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            item { Text("Set up WhatsApp updates",style=MaterialTheme.typography.titleLarge);Text("You need an OpenWA gateway once. Sharing trips and delay reports to WhatsApp works without it.") }
            item { HelpStep("1 · Run or get access to a gateway","OpenWA is a separate server, not a WhatsApp login inside CT Trains. Run rmyndharis/OpenWA on a computer/server you control, or use a gateway you trust. The server processes messages from its linked account, so WhatsApp updates are not entirely phone-local.") }
            item {
                HelpStep("Installing with Docker","On a computer with Git and Docker installed, clone the official repository, enter its folder, then start it. Follow its current README for environment settings and updates.")
                SelectionContainer { Text("git clone https://github.com/rmyndharis/OpenWA.git\ncd OpenWA\ndocker compose up -d",style=MaterialTheme.typography.bodySmall) }
                TextButton(onClick={ uri.openUri("https://github.com/rmyndharis/OpenWA/blob/main/docs/README.md") }) { Text("Open official installation instructions") }
            }
            item { HelpStep("2 · Give it a reachable HTTPS address","The default API is http://localhost:2785/api on the server. Put it behind HTTPS with a valid certificate, using your host's reverse proxy/domain setup. Your phone needs that HTTPS address, for example https://wa.your-domain/api. localhost on your phone means the phone, not your server. CT Trains cannot provision hosting or a certificate for you.") }
            item { HelpStep("3 · Link the WhatsApp account","Open the gateway dashboard. Use Sessions to create and start an account, then link it using WhatsApp → Linked devices and the dashboard QR code or pairing flow. For reading channels, set ENGINE_TYPE=whatsapp-web.js on the gateway. Baileys does not support the channel history endpoint used here. A dedicated account is useful. The linked account must belong to the group or follow the channel you want to read.") }
            item { HelpStep("4 · Create a Reader key","In the OpenWA dashboard's API key management, create a key with the viewer role (read only). Restrict allowedSessions to this account's UUID. Add allowedChats restrictions for the exact groups/channels you want when supported by your installed version. Copy the new key when shown; keep your admin key out of this app. If the server administrator manages keys, ask them for a restricted Reader key and HTTPS address.") }
            item { HelpStep("What is the session ID?","It is the UUID of the linked account, such as 550e8400-e29b-41d4-a716-446655440000. It is not your phone number, the account name, a group ID or a channel invite link. The app's Find linked accounts button retrieves the accounts visible to your Reader key, so you normally do not need to copy this UUID.") }
            item { HelpStep("5 · Connect in CT Trains","Return to WhatsApp updates. Enter the HTTPS gateway website and Reader key, tap Find linked accounts, choose the ready account and tap Test & connect reader. We add /api if you entered only a website origin; a supplied custom path is kept. If listing accounts is unavailable, choose Enter account ID manually and paste its UUID. Credentials are encrypted on this installation and excluded from profile backups.") }
            item { HelpStep("6 · Choose the actual transit feed","Follow PRASA's channel in the linked WhatsApp account, then tap Browse subscribed channels here and choose it. Verify the publisher yourself; a name alone does not prove official identity. For a group, use the exact @g.us ID shown by your gateway. For a channel, use its @newsletter ID; a whatsapp.com/channel invitation code is different. Your chosen service tag does not change a feed's authority.") }
            item { HelpStep("If something fails","401/403: check key expiry and account/feed permissions. 404: check /api and the UUID. 409 or an account that is not ready: start it and finish linking in the dashboard. 501: channel reading needs whatsapp-web.js. A blank list can mean this account follows no channels or this key cannot see them. Connection failure: check HTTPS certificate, network and gateway availability. Dated saved posts remain readable; official schedules keep working independently.") }
            item { HelpStep("Pausing and removing access","Pause reader stops app checks. Disconnect & erase inbox removes this phone's saved credentials and posts. Revoke the key or unlink the linked device in WhatsApp/OpenWA to remove server access too. Optional background checks run about every 15 minutes; they are not instant alerts. OpenWA is unofficial and changes in WhatsApp may break this alpha feature.") }
            item { TextButton(onClick={ uri.openUri("https://github.com/rmyndharis/OpenWA/blob/main/docs/07-api-collection.md") }) { Text("Open current OpenWA API documentation") };TextButton(onClick=onDismiss) { Text("Back to connection") } }
        }
    }
}

@Composable private fun HelpStep(title: String,body: String) {
    Column(verticalArrangement=Arrangement.spacedBy(5.dp)) { Text(title,style=MaterialTheme.typography.titleMedium);Text(body,style=MaterialTheme.typography.bodyMedium) }
}
