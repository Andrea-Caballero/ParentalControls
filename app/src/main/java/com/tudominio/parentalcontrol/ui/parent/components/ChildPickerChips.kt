package com.tudominio.parentalcontrol.ui.parent.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.tudominio.parentalcontrol.domain.model.Child

/**
 * Material 3 filter-chip row that lets the parent scope the dashboard
 * to a single child. Renders one `Todos` chip first, then one chip per
 * [Child] in the supplied list. Hidden by the caller when
 * `children.size < 2` (see `DashboardScaffold`).
 *
 * testTags:
 *   - `child_picker` — the wrapping `LazyRow` (satisfies the q2_gap
 *     contract `q2_gap_dashboard_renders_child_picker_or_filter_control`)
 *   - `child_picker_chip_all` — the "Todos" chip
 *   - `child_picker_chip_$childId` — one chip per child
 *
 * Snake-case per project convention (`device_card`, `pairing_qr`,
 * `auth_missing_sign_in_cta`).
 *
 * B.2 of `feat-multi-child-picker` (Change B). The
 * `fix-rename-child-dialog` apply phase adds `onLongPress` (Q1=m,
 * manual trigger) — a long-press on a non-Todos chip fires the
 * callback so the dashboard can open `RenameChildDialog`.
 *
 * `combinedClickable` is the Material 3-recommended way to add a
 * long-press handler alongside the regular onClick tap target; without
 * it, the chip swallows long-press and the rename affordance is
 * undiscoverable. The long-press detection is provided by Compose's
 * `combinedClickable` (ExperimentalFoundationApi).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChildPickerChips(
    children: List<Child>,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
    onLongPress: (Child) -> Unit = {}
) {
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .testTag("child_picker"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "child_picker_chip_all") {
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { Text("Todos") },
                modifier = Modifier.testTag("child_picker_chip_all")
            )
        }
        items(children, key = { it.id }) { child ->
            FilterChip(
                selected = selected == child.id,
                onClick = { onSelect(child.id) },
                label = { Text(child.firstName) },
                modifier = Modifier
                    .testTag("child_picker_chip_${child.id}")
                    .combinedClickable(
                        onClick = { onSelect(child.id) },
                        onLongClick = { onLongPress(child) }
                    )
            )
        }
    }
}
