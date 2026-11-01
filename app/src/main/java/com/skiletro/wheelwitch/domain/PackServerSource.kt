package com.skiletro.wheelwitch.domain

import com.skiletro.wheelwitch.model.ServerInfo

/** Backend the pack manager reads from: update manifest and full-zip pointer. */
interface PackServerSource {
  fun fetchServerInfo(): Result<ServerInfo>
  fun fetchFullZipUrl(): String
}