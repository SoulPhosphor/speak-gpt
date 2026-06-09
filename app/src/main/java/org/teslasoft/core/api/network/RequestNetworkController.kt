/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **************************************************************************/

package org.teslasoft.core.api.network

import com.google.gson.Gson

import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okio.IOException
import java.lang.String.valueOf
import java.util.*
import java.util.concurrent.TimeUnit

open class RequestNetworkController {
    companion object {
        const val GET = "GET"

        const val REQUEST_PARAM = 0

        private const val SOCKET_TIMEOUT: Long = 15000
        private const val READ_TIMEOUT: Long = 25000

        protected var client: OkHttpClient? = null
        private var mInstance: RequestNetworkController? = null

        @Synchronized
        fun getInstance(): RequestNetworkController? {
            if (mInstance == null) {
                mInstance = RequestNetworkController()
            }
            return mInstance
        }
    }

    private fun getClient(): OkHttpClient {
        if (client == null) {
            // Default OkHttp TLS: system trust store, full certificate and
            // hostname verification. The previous custom SSL setup disabled
            // hostname verification entirely, which allowed any valid
            // certificate for any domain to impersonate the API host.
            client = OkHttpClient.Builder()
                .connectTimeout(SOCKET_TIMEOUT, TimeUnit.MILLISECONDS)
                .readTimeout(READ_TIMEOUT, TimeUnit.MILLISECONDS)
                .writeTimeout(READ_TIMEOUT, TimeUnit.MILLISECONDS)
                .build()
        }

        return client as OkHttpClient
    }

    fun execute(
        requestNetwork: RequestNetwork,
        method: String,
        url: String,
        tag: String,
        requestListener: RequestNetwork.RequestListener
    ) {
        val reqBuilder: Request.Builder = Request.Builder()
        val headerBuilder: Headers.Builder = Headers.Builder()

        if (requestNetwork.getHeaders().isNotEmpty()) {
            val headers: HashMap<String, Any> = requestNetwork.getHeaders()

            for (header: MutableMap.MutableEntry<String, Any> in headers.entries) {
                headerBuilder.add(header.key, valueOf(header.value))
            }
        }

        try {
            if (requestNetwork.getRequestType() == REQUEST_PARAM) {
                if (method == GET) {
                    get(reqBuilder, url, requestNetwork, headerBuilder)
                } else {
                    nonGet(reqBuilder, url, requestNetwork, headerBuilder, method)
                }
            } else {
                val reqBody: RequestBody = Gson().toJson(requestNetwork.getParams())
                    .toRequestBody("application/json".toMediaTypeOrNull())

                if (method == GET) {
                    reqBuilder.url(url).headers(headerBuilder.build()).get()
                } else {
                    reqBuilder.url(url).headers(headerBuilder.build()).method(method, reqBody)
                }
            }

            val req: Request = reqBuilder.build()

            clientCall(req, requestNetwork, tag, requestListener)
        } catch (e: Exception) {
            requestListener.onErrorResponse(tag, e.message!!)
        }
    }

    private fun clientCall(
        req: Request,
        requestNetwork: RequestNetwork,
        tag: String,
        requestListener: RequestNetwork.RequestListener
    ) {
        getClient().newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                requestNetwork.getActivity().runOnUiThread {
                    requestListener.onErrorResponse(
                        tag, e.message!!
                    )
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody: String = response.body?.string()?.trim().orEmpty()

                requestNetwork.getActivity().runOnUiThread {
                    requestListener.onResponse(
                        tag, responseBody
                    )
                }
            }
        })
    }

    private fun get(
        reqBuilder: Request.Builder,
        url: String,
        requestNetwork: RequestNetwork,
        headerBuilder: Headers.Builder
    ) {
        val httpBuilder: HttpUrl.Builder =
            url.toHttpUrlOrNull()?.newBuilder() ?: throw NullPointerException(
                "unexpected url: $url"
            )

        if (requestNetwork.getParams().isNotEmpty()) {
            val params: HashMap<String, Any> = requestNetwork.getParams()

            for (param: MutableMap.MutableEntry<String, Any> in params.entries) {
                httpBuilder.addQueryParameter(param.key, valueOf(param.value))
            }
        }

        reqBuilder.url(httpBuilder.build()).headers(headerBuilder.build()).get()
    }

    private fun nonGet(
        reqBuilder: Request.Builder,
        url: String,
        requestNetwork: RequestNetwork,
        headerBuilder: Headers.Builder,
        method: String
    ) {
        val formBuilder: FormBody.Builder = FormBody.Builder()

        if (requestNetwork.getParams().isNotEmpty()) {
            val params: HashMap<String, Any> = requestNetwork.getParams()

            for (param: MutableMap.MutableEntry<String, Any> in params.entries) {
                formBuilder.add(param.key, valueOf(param.value))
            }
        }

        val reqBody: RequestBody = formBuilder.build()
        reqBuilder.url(url).headers(headerBuilder.build()).method(method, reqBody)
    }
}
