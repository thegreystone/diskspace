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
import se.hirt.diskspace.ui.theme.ColorScheme;

import java.util.function.ToIntFunction;

/**
 * A colouring strategy — a recipe for turning a {@link DirectoryNode} into a fill colour. Each {@code DiskView} asks
 * its configured mode for a fresh {@link NodeColorResolver} at construction time, so the mode implementation can keep
 * per-view caches without worrying about concurrency.
 * <p>Implementations live alongside in {@code se.hirt.diskspace.ui.render} and are registered statically in
 * {@link ColoringModes#all()}. To add one: implement this interface and append a constructor call to the list
 * there.</p>
 */
public interface ColoringMode {

	/**
	 * Stable identifier used for persistence in the settings file. Must be unique across registered modes, lowercase,
	 * kebab-case. Changing this for an existing mode silently resets the user's saved preference to the default — pick
	 * once and don't churn it.
	 */
	String id();

	/** Human-readable name shown in the Preferences dialog (e.g. "Classic", "Black &amp; White"). */
	String displayName();

	/**
	 * One- or two-sentence explanation of what this mode colours and why — surfaced in the Preferences dialog under the
	 * picker so users can choose without having to test each one. Plain text; no markup.
	 */
	String description();

	/**
	 * Build a resolver for one {@code DiskView}'s lifetime. The returned resolver owns its own caches and lifecycle
	 * state (see {@link NodeColorResolver}'s default hooks).
	 *
	 * @param scheme
	 * 		the current colour scheme (dark / light)
	 * @param rankOf
	 * 		callback that returns a child's index in its parent's size-descending sibling list; supplied by the host so
	 * 		modes can colour by rank without duplicating the sort. {@code 0} means "the largest child of its parent".
	 */
	NodeColorResolver createResolver(ColorScheme scheme, ToIntFunction<DirectoryNode> rankOf);
}
