package com.skiletro.wheelwitch.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.skiletro.wheelwitch.R
import com.skiletro.wheelwitch.util.mii.MII_IMAGE_BASE_URL
import java.net.URLEncoder

/** Corner radius applied to rendered Mii face bitmaps. */
private val MiiFaceCorner = 10.dp

private val MiiFadeBrush =
  Brush.verticalGradient(
    colorStops =
      arrayOf(
        0.0f to Color.Black,
        0.75f to Color.Black,
        1.0f to Color.Transparent,
      )
  )

private fun Modifier.miiFade() =
  this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
      drawContent()
      drawRect(brush = MiiFadeBrush, blendMode = BlendMode.DstIn)
    }

/** Default Mii RFL data shown when the real Mii fetch fails. */
private const val DEFAULT_MII_DATA_BASE64 =
  "AwAIMK89McaWg1Qak3k3LDSvLMdoMwAAIgBNAGkAaQAAAAAAAAAAAAAAAAAAAEBAAAAhAQJoRBgmNEYUgRIXaA0AACkAUkhQVAAgAEIAbwBtAGIAZQByAAAAAAAAACHC"

/** Builds the mii-unsecure face URL for a given RFL base64 payload. */
private fun miiDataUrl(data: String): String =
  "$MII_IMAGE_BASE_URL?data=${URLEncoder.encode(data, "UTF-8")}&width=300&type=face&shaderType=switch"

/**
 * Prefers [imageBase64] as Coil's model, otherwise requests a face for [miiDataBase64].
 * Missing input shows a spinner. Request errors use a separate default-face painter.
 */
@Composable
fun MiiFace(
  imageBase64: String?,
  miiDataBase64: String?,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val inlinePng = imageBase64
  val rflData = miiDataBase64
  val data: Any? =
    when {
      inlinePng != null -> inlinePng
      rflData != null -> miiDataUrl(rflData)
      else -> null
    }

  if (data == null) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
      CircularProgressIndicator(strokeWidth = 3.dp)
    }
    return
  }

  val mainRequest =
    remember(data) {
      ImageRequest.Builder(context)
        .data(data)
        .crossfade(true)
        .build()
    }
  val defaultPainter =
    rememberAsyncImagePainter(
      model =
        ImageRequest.Builder(context)
          .data(miiDataUrl(DEFAULT_MII_DATA_BASE64))
          .crossfade(true)
          .build()
    )
  val fallbackPainter = painterResource(R.drawable.ic_face_up)

  Box(modifier = modifier, contentAlignment = Alignment.Center) {
    AsyncImage(
      model = mainRequest,
      contentDescription = null,
      modifier = Modifier.fillMaxSize().miiFade().clip(RoundedCornerShape(MiiFaceCorner)),
      placeholder = null,
      error = defaultPainter,
      fallback = fallbackPainter,
    )
  }
}
