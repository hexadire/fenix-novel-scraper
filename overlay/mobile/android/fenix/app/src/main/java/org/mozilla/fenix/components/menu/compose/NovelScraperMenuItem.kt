/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.components.menu.compose

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.R
import org.mozilla.fenix.ext.components
import org.mozilla.fenix.novel.NovelScraperActivity

/**
 * "Download novel" in the tools of the main menu: opens the novel scraper with the page that is open in the browser.
 * It is one item added to the tools group of [MainMenu]; nothing else of the menu is changed.
 */
@Composable
internal fun NovelScraperMenuItem() {
    val context = LocalContext.current
    MenuItem(
        label = stringResource(id = R.string.novel_menu_item),
        beforeIconPainter = painterResource(id = iconsR.drawable.mozac_ic_download_24),
        onClick = {
            val address = context.components.core.store.state.selectedTab?.content?.url
            val activity = context.findActivity()
            if (activity != null) {
                if (activity is FragmentActivity) closeMenu(activity.supportFragmentManager)
                activity.startActivity(NovelScraperActivity.intentFor(activity, address))
            }
        },
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Closes the menu (a dialog fragment) so that it is not still open when the person comes back. */
private fun closeMenu(manager: FragmentManager) {
    for (fragment in manager.fragments) {
        if (fragment is DialogFragment) fragment.dismissAllowingStateLoss()
        if (fragment.isAdded) closeMenu(fragment.childFragmentManager)
    }
}
