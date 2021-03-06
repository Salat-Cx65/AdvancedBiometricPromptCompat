/*
 *  Copyright (c) 2021 Sergey Komlach aka Salat-Cx65; Original project: https://github.com/Salat-Cx65/AdvancedBiometricPromptCompat
 *  All rights reserved.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package dev.skomlach.common.cryptostorage

import android.app.Application
import android.content.SharedPreferences
import java.util.*

class EncryptedPreferencesProvider(private val application: Application) :
    CryptoPreferencesProvider {
    override fun getCryptoPreferences(name: String): SharedPreferences {
        var preferences = cache[name]
        if (preferences == null) {
            preferences = CryptoPreferencesImpl(application, name)
            cache[name] = preferences
        }
        return preferences
    }

    companion object {
        private val cache: MutableMap<String, SharedPreferences> = HashMap()
    }
}