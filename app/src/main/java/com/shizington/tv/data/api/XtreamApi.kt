package com.shizington.tv.data.api

import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.QueryMap
import retrofit2.http.Streaming

interface XtreamApi {
    /** Every Xtream Codes call goes through player_api.php; `action` selects the endpoint. */
    @Streaming
    @GET("player_api.php")
    suspend fun call(@QueryMap params: Map<String, String>): ResponseBody
}
