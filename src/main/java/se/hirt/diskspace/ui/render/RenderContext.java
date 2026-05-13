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
package se.hirt.diskspace.ui.render;

import se.hirt.diskspace.model.DirectoryNode;
import se.hirt.diskspace.model.Volume;
import se.hirt.diskspace.scan.Scanner;

/**
 * Per-frame, read-only snapshot of "what to draw." Built by the view (DiskView) once per render pulse and handed to
 * the active {@link Visualization}. Excludes anything animation-related — each visualization tracks its own animation
 * state internally.
 *
 * @param scanRoot              root of the scan tree
 * @param viewRoot              currently drilled-in node; same as {@code scanRoot} when at the top level
 * @param hiddenNode            the synthetic "Hidden" child of {@code scanRoot}, or {@code null}; visualisations may
 *                              pin it to the end of a sort regardless of size so it doesn't shift around mid-scan
 * @param hoverNode             the {@link DirectoryNode} the cursor is over, or {@code null}
 * @param hoveringHub           cursor is over the centre hub
 * @param hoveringFreeSpace     cursor is over the free-space arc / cell
 * @param hoveringUnaccounted   cursor is over the "Unaccounted" arc / cell
 * @param target                the {@link Volume} being visualised — used for capacity figures
 * @param scanning              {@code true} while a scan is in flight; visualisations may pulse a progress indicator
 * @param progressFiles         file count seen so far in the active scan
 * @param progressBytes         bytes seen so far in the active scan
 * @param progressPath          path currently being scanned, for the hub progress tail; may be {@code null}
 * @param hubState              scanner-supplied overrides for the sunburst hub (title / subtitle / progress arc),
 *                              snapshot at render time; never {@code null}, may be {@link Scanner.HubState#DEFAULT}
 */
public record RenderContext(DirectoryNode scanRoot, DirectoryNode viewRoot, DirectoryNode hiddenNode,
							DirectoryNode hoverNode, boolean hoveringHub, boolean hoveringFreeSpace,
							boolean hoveringUnaccounted, Volume target, boolean scanning, long progressFiles,
							long progressBytes, String progressPath, Scanner.HubState hubState) {
}
