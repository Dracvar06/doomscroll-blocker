package cat.doorman.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cat.doorman.app.R

/**
 * What somebody is shown once, the first time they open Doorman.
 *
 * Four pages, not a tour. A walkthrough that points at every switch is a
 * walkthrough nobody finishes, and Doorman's screens are already labelled; what
 * a newcomer cannot work out from the labels is the *shape* of the thing --
 * that it holds screens rather than apps, that loosening a limit is deliberately
 * slow, and that there is a way in when they really need one. Those are the
 * four pages.
 *
 * Skippable from the first page. An introduction that cannot be closed is a
 * toll gate, and Doorman is asking for a lot of trust on the very next screen.
 */
@Composable
fun Tutorial(
    languageSupported: Boolean,
    languageTag: String?,
    onPickLanguage: (String?) -> Unit,
    onCoffee: () -> Unit,
    onDone: () -> Unit,
) {
    var page by remember { mutableIntStateOf(0) }
    val pages = tutorialPages()
    val last = page == pages.lastIndex

    // The page scrolls, the controls do not. Next and Skip sat directly under
    // the text, so they jumped up and down the screen from page to page and
    // left the bottom half -- where a thumb actually is -- empty.
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(top = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(pages[page].icon),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(40.dp),
                )
            }
            Text(
                stringResource(pages[page].title),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                stringResource(pages[page].body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // The language picker on the first page rather than a page of its
            // own. Doorman already follows the phone's language, so for most
            // people this is a confirmation, not a question -- and picking one
            // recreates the activity with the walkthrough back on page one, in
            // the language they just chose. On Android 12 and older the
            // section simply says it follows the phone.
            if (page == 0) {
                SurfaceCard {
                    LanguageSection(
                        supported = languageSupported,
                        currentTag = languageTag,
                        onPick = onPickLanguage,
                    )
                }
            }

            // The coffee, once, at the end, as a text button. Somebody who
            // has read four pages about an app that will get in their way has
            // earned being told how it is paid for, and being told exactly
            // once.
            if (last) {
                Text(
                    stringResource(R.string.support_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onCoffee) {
                    Text(stringResource(R.string.action_coffee))
                }
            }
        }

        Column(
            modifier = Modifier.padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Dots(count = pages.size, current = page)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Skip stays put rather than disappearing on the last page: a
                // button that moves is a button somebody presses by accident.
                TextButton(onClick = onDone) {
                    Text(stringResource(R.string.tutorial_skip))
                }
                Button(onClick = { if (last) onDone() else page++ }) {
                    Text(
                        stringResource(
                            if (last) R.string.tutorial_start else R.string.tutorial_next,
                        ),
                    )
                }
            }
        }
    }
}

private class TutorialPage(val title: Int, val body: Int, val icon: Int)

// The door shut on the first page and ajar on the last: Doorman holds the
// feed, and there is always a way in.
private fun tutorialPages() = listOf(
    TutorialPage(R.string.tutorial_1_title, R.string.tutorial_1_body, R.drawable.ic_door_closed),
    TutorialPage(R.string.tutorial_2_title, R.string.tutorial_2_body, R.drawable.ic_tune),
    TutorialPage(R.string.tutorial_3_title, R.string.tutorial_3_body, R.drawable.ic_hourglass),
    TutorialPage(R.string.tutorial_4_title, R.string.tutorial_4_body, R.drawable.ic_door_open),
)

@Composable
private fun Dots(count: Int, current: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(count) { index ->
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        color = if (index == current) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = CircleShape,
                    ),
            )
        }
    }
}
