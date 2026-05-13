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
 * The default DaisyDisk-inspired colouring: each top-level folder is assigned a saturated palette colour, and its
 * descendants inherit hue from their family root with a soft lightening + slight hue shift as they get smaller and
 * deeper. Large files render in neutral grey so they don't compete visually with folder structure.
 */
public final class ClassicColoringMode implements ColoringMode {

	@Override
	public String id() {
		return "classic";
	}

	@Override
	public String displayName() {
		return "Classic";
	}

	@Override
	public String description() {
		return "Each top-level folder gets a saturated colour from a 12-hue palette; " + "descendants inherit their family's hue and lighten toward the rim. " + "Large files use neutral grey so folder structure stays the focal point.";
	}

	@Override
	public NodeColorResolver createResolver(ColorScheme scheme, ToIntFunction<DirectoryNode> rankOf) {
		return new NodeColorResolverImpl(scheme, rankOf);
	}
}
