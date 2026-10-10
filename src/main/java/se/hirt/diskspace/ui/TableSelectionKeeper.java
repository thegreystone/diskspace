/*
 * Copyright (C) 2026 Marcus Hirt
 *
 * This software is free:
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. The name of the author may not be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESSED OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 * THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package se.hirt.diskspace.ui;

import javafx.collections.ObservableList;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TablePosition;
import javafx.scene.control.TableView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Replaces a {@link TableView}'s rows while keeping the user's multi-selection, keyboard focus and Shift-range anchor
 * on the same logical rows.
 * <p>JavaFX's selection model treats {@code setAll} on the items list as "the list totally changed": it clears
 * everything and re-selects only the single lead item ({@code TableView.updateDefaultSelection}), so a Shift-range or
 * Ctrl-toggled set collapses to one row every time the list is re-sorted. The focus model does the same, and the
 * Shift-range anchor that {@code CellBehaviorBase} stores on the control is a bare row index, so after a reorder it
 * would point at whichever row now sits there. Live-updating tables (sizes growing during a scan) hit all three on
 * every tick.
 * <p>Rows are matched by a caller-supplied identity key rather than {@code equals}, so rows whose value-based equality
 * includes mutable state (a file's size, a node's state) still line up across refreshes.
 */
final class TableSelectionKeeper {

	/** Key under which JavaFX's {@code CellBehaviorBase} keeps a control's Shift-range anchor in its properties map. */
	static final String FX_ANCHOR_KEY = "anchor";

	private TableSelectionKeeper() {
	}

	/**
	 * Replaces {@code items} (the list backing {@code table}) with {@code replacement}, then re-applies the previous
	 * selection, focus and anchor to the rows whose {@code key} matches.
	 *
	 * @param table
	 * 		the table whose selection, focus and anchor are preserved
	 * @param items
	 * 		the observable list the table is bound to
	 * @param replacement
	 * 		the new row contents
	 * @param key
	 * 		row identity; two rows are "the same" when their keys are equal
	 */
	static <T> void replaceAll(
			TableView<T> table, ObservableList<T> items, List<? extends T> replacement, Function<? super T, Object> key) {
		TableView.TableViewSelectionModel<T> sm = table.getSelectionModel();
		List<T> selected = new ArrayList<>(sm.getSelectedItems());
		T lead = sm.getSelectedItem();
		int focusedIdx = table.getFocusModel().getFocusedIndex();
		T focused = (focusedIdx >= 0 && focusedIdx < items.size()) ? items.get(focusedIdx) : null;
		Object anchorObj = table.getProperties().get(FX_ANCHOR_KEY);
		TablePosition<?, ?> anchorPos = (anchorObj instanceof TablePosition<?, ?> tp) ? tp : null;
		T anchor = (anchorPos != null && anchorPos.getRow() >= 0 && anchorPos.getRow() < items.size()) ?
				items.get(anchorPos.getRow()) : null;

		items.setAll(replacement);

		Map<Object, Integer> indexByKey = new HashMap<>(replacement.size() * 2);
		for (int i = 0; i < replacement.size(); i++)
			indexByKey.putIfAbsent(key.apply(replacement.get(i)), i);

		if (!selected.isEmpty()) {
			List<Integer> indices = new ArrayList<>(selected.size());
			for (T row : selected) {
				if (row == lead)
					continue;
				Integer idx = indexByKey.get(key.apply(row));
				if (idx != null)
					indices.add(idx);
			}
			// Re-select the previous lead row last so it remains the lead (selectedItem) afterwards.
			Integer leadIdx = (lead != null) ? indexByKey.get(key.apply(lead)) : null;
			if (leadIdx != null)
				indices.add(leadIdx);
			sm.clearSelection();
			if (!indices.isEmpty()) {
				int[] rest = new int[indices.size() - 1];
				for (int i = 1; i < indices.size(); i++)
					rest[i - 1] = indices.get(i);
				sm.selectIndices(indices.get(0), rest);
			}
		}
		if (focused != null) {
			Integer idx = indexByKey.get(key.apply(focused));
			if (idx != null)
				table.getFocusModel().focus(idx);
		}
		if (anchorPos != null) {
			Integer idx = (anchor != null) ? indexByKey.get(key.apply(anchor)) : null;
			if (idx != null) {
				@SuppressWarnings("unchecked")
				TableColumn<T, ?> col = (TableColumn<T, ?>) anchorPos.getTableColumn();
				table.getProperties().put(FX_ANCHOR_KEY, new TablePosition<>(table, idx, col));
			} else {
				table.getProperties().remove(FX_ANCHOR_KEY);
			}
		}
	}
}
