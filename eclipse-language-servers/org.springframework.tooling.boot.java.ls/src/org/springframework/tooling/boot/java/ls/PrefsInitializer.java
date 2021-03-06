/*******************************************************************************
 * Copyright (c) 2017 Pivotal, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Pivotal, Inc. - initial API and implementation
 *******************************************************************************/
package org.springframework.tooling.boot.java.ls;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;

/**
 * Preferences initializer for Boot-Java LS extension
 * 
 * @author Alex Boyko
 *
 */
public class PrefsInitializer extends AbstractPreferenceInitializer {

	public PrefsInitializer() {
	}

	@Override
	public void initializeDefaultPreferences() {
		BootJavaLanguageServerPlugin.getDefault().getPreferenceStore().setDefault(Constants.PREF_BOOT_HINTS, true);
	}

}
