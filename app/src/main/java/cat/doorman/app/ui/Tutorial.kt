package cat.doorman.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(pages[page].title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            stringResource(pages[page].body),
            style = MaterialTheme.typography.bodyLarge,
        )

        // The language picker on the first page rather than a page of its
        // own. Doorman already follows the phone's language, so for most
        // people this is a confirmation, not a question -- and picking one
        // recreates the activity with the walkthrough back on page one, in
        // the language they just chose. On Android 12 and older the section
        // simply says it follows the phone.
        if (page == 0) {
            LanguageSection(
                supported = languageSupported,
                currentTag = languageTag,
                onPick = onPickLanguage,
            )
        }

        // The coffee, once, at the end, as a text button. Somebody who has
        // read four pages about an app that will get in their way has earned
        // being told how it is paid for, and being told exactly once.
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

        Spacer(Modifier.height(8.dp))
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

private class TutorialPage(val title: Int, val body: Int)

private fun tutorialPages() = listOf(
    TutorialPage(R.string.tutorial_1_title, R.string.tutorial_1_body),
    TutorialPage(R.string.tutorial_2_title, R.string.tutorial_2_body),
    TutorialPage(R.string.tutorial_3_title, R.string.tutorial_3_body),
    TutorialPage(R.string.tutorial_4_title, R.string.tutorial_4_body),
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
