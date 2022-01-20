/*
 * Copyright 2022 Jedlix B.V. The Netherlands
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 */

package com.jedlix.sdk.example.authentication

import android.content.Context
import android.util.Log
import com.auth0.android.Auth0
import com.auth0.android.authentication.AuthenticationAPIClient
import com.auth0.android.authentication.AuthenticationException
import com.auth0.android.authentication.storage.CredentialsManager
import com.auth0.android.authentication.storage.CredentialsManagerException
import com.auth0.android.authentication.storage.SharedPreferencesStorage
import com.auth0.android.callback.Callback
import com.auth0.android.jwt.DecodeException
import com.auth0.android.jwt.JWT
import com.auth0.android.result.Credentials
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class Auth0Authentication(
    clientId: String,
    domain: String,
    private val audience: String,
    private val userIdentifierKey: String,
    private val coroutineScope: CoroutineScope,
    context: Context
) : Authentication {

    companion object {
        const val realm = "Username-Password-Authentication"
        const val scope = "openid offline_access create:connectsession read:connectsession modify:connectsession read:vehicle delete:vehicle"
    }

    private val auth0 = Auth0(
        clientId,
        domain
    )

    private val credentialsManager = CredentialsManager(
        AuthenticationAPIClient(auth0),
        SharedPreferencesStorage(context)
    )

    override val isSignedIn: Boolean
        get() = credentialsManager.hasValidCredentials()

    override fun clearCredentials() {
        credentialsManager.clearCredentials()
    }

    override suspend fun getAccessToken(): String? {
        val tokenResult = CompletableDeferred<String?>()
        if (credentialsManager.hasValidCredentials()) {
            coroutineScope.launch(Dispatchers.IO) {
                credentialsManager.getCredentials(
                    object : Callback<Credentials, CredentialsManagerException> {

                        override fun onSuccess(result: Credentials) {
                            tokenResult.complete(result.accessToken)
                        }

                        override fun onFailure(error: CredentialsManagerException) {
                            tokenResult.complete(null)
                        }
                    }
                )
            }
        } else {
            tokenResult.complete(null)
        }
        return tokenResult.await()
    }

    override suspend fun renewAccessToken(): String? = getAccessToken() // Auth0 will automatically refresh the token when requested

    suspend fun signIn(email: String, password: String): Authentication.SignInResponse {
        val request = AuthenticationAPIClient(auth0)
            .login(email, password, realm)
            .setScope(scope)
            .setAudience(audience)

        val response = CompletableDeferred<Credentials>()
        request.start(object : Callback<Credentials, AuthenticationException> {
            override fun onSuccess(result: Credentials) {
                response.complete(result)
            }

            override fun onFailure(error: AuthenticationException) {
                response.completeExceptionally(error)
            }
        })
        return try {
            val credentials = response.await()
            credentialsManager.saveCredentials(credentials)
            parseCredentials(credentials)
        } catch (e: AuthenticationException) {
            Log.e("Authentication", "Authentication Failed: ${e.getDescription()}")
            Authentication.SignInResponse.Failed(e.getDescription())
        }
    }

    override suspend fun getCredentials(): Authentication.SignInResponse {
        val response = CompletableDeferred<Authentication.SignInResponse>()
        credentialsManager.getCredentials(
            object : Callback<Credentials, CredentialsManagerException> {

                override fun onSuccess(result: Credentials) {
                    response.complete(parseCredentials(result))
                }

                override fun onFailure(error: CredentialsManagerException) {
                    response.complete(Authentication.SignInResponse.Failed(error.localizedMessage ?: ""))
                }
            }
        )
        return response.await()
    }

    private fun parseCredentials(credentials: Credentials): Authentication.SignInResponse {
        return try {
            val jwt = JWT(credentials.accessToken)
            val userIdentifier = jwt.getClaim(userIdentifierKey).asString()
            if (userIdentifier.isNullOrEmpty()) {
                Authentication.SignInResponse.Failed("Missing user identifier")
            } else {
                Authentication.SignInResponse.Success(userIdentifier)
            }
        } catch (e: DecodeException) {
            Log.e("JWT", "Invalid JWT: ${e.message}")
            Authentication.SignInResponse.Failed("Invalid JWT: ${e.message}")
        }
    }
}
