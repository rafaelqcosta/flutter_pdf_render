package jp.espresso3389.pdf_render

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_PRINT
import android.os.ParcelFileDescriptor
import android.os.ParcelFileDescriptor.MODE_READ_ONLY
import android.util.SparseArray
import android.view.Surface
import androidx.annotation.NonNull
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.view.TextureRegistry
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer

/** PdfRenderPlugin */
class PdfRenderPlugin : FlutterPlugin, MethodCallHandler {
  private lateinit var channel: MethodChannel
  private lateinit var flutterPluginBinding: FlutterPlugin.FlutterPluginBinding
  private val documents: SparseArray<PdfRenderer> = SparseArray()
  private var lastDocId: Int = 0
  private val textures: SparseArray<TextureRegistry.SurfaceTextureEntry> = SparseArray()

  override fun onAttachedToEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
    flutterPluginBinding = binding
    channel = MethodChannel(binding.binaryMessenger, "pdf_render")
    channel.setMethodCallHandler(this)
  }

  override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
    channel.setMethodCallHandler(null)
  }

  override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
    try {
      when (call.method) {
        "file" -> result.success(registerNewDoc(openFileDoc(call.arguments as String)))
        "asset" -> result.success(registerNewDoc(openAssetDoc(call.arguments as String)))
        "data" -> result.success(registerNewDoc(openDataDoc(call.arguments as ByteArray)))
        "close" -> {
          close(call.arguments as Int)
          result.success(0)
        }
        "info" -> {
          val (renderer, id) = getDoc(call)
          result.success(getInfo(renderer, id))
        }
        "page" -> result.success(openPage(call.arguments as HashMap<String, Any>))
        "render" -> render(call.arguments as HashMap<String, Any>, result)
        "releaseBuffer" -> {
          releaseBuffer(call.arguments as Long)
          result.success(0)
        }
        "allocTex" -> result.success(allocTex())
        "releaseTex" -> {
          releaseTex(call.arguments as Int)
          result.success(0)
        }
        "updateTex" -> result.success(updateTex(call.arguments as HashMap<String, Any>))
        else -> result.notImplemented()
      }
    } catch (e: Exception) {
      result.error("exception", "Internal error: ${e.message}", e)
    }
  }

  private fun registerNewDoc(renderer: PdfRenderer): HashMap<String, Any> {
    val id = ++lastDocId
    documents.put(id, renderer)
    return getInfo(renderer, id)
  }

  private fun getDoc(call: MethodCall): Pair<PdfRenderer, Int> {
    val id = call.arguments as Int
    return Pair(documents[id], id)
  }

  private fun getInfo(pdfRenderer: PdfRenderer, id: Int): HashMap<String, Any> {
    return hashMapOf(
      "docId" to id,
      "pageCount" to pdfRenderer.pageCount,
      "verMajor" to 1,
      "verMinor" to 7,
      "isEncrypted" to false,
      "allowsCopying" to false,
      "allowsPrinting" to false
    )
  }

  private fun close(id: Int) {
    documents[id]?.let {
      it.close()
      documents.remove(id)
    }
  }

  private fun openFileDoc(path: String): PdfRenderer {
    val fd = ParcelFileDescriptor.open(File(path), MODE_READ_ONLY)
    return PdfRenderer(fd)
  }

  private fun openAssetDoc(assetName: String): PdfRenderer {
    val key = flutterPluginBinding.flutterAssets.getAssetFilePathByName(assetName)
    val context = flutterPluginBinding.applicationContext
    context.assets.open(key).use { input ->
      return copyToTempFileAndOpenDoc { input.copyTo(it) }
    }
  }

  private fun openDataDoc(data: ByteArray): PdfRenderer {
    return copyToTempFileAndOpenDoc { it.write(data) }
  }

  private fun copyToTempFileAndOpenDoc(write: (OutputStream) -> Unit): PdfRenderer {
    val file = File.createTempFile("pdf_temp", ".pdf", null)
    file.outputStream().use { write(it) }
    val fd = ParcelFileDescriptor.open(file, MODE_READ_ONLY)
    return PdfRenderer(fd)
  }

  private fun openPage(args: HashMap<String, Any>): HashMap<String, Any>? {
    val docId = args["docId"] as? Int ?: return null
    val pageNum = args["pageNumber"] as? Int ?: return null
    val renderer = documents[docId] ?: return null
    if (pageNum < 1 || pageNum > renderer.pageCount) return null

    renderer.openPage(pageNum - 1).use {
      return hashMapOf(
        "docId" to docId,
        "pageNumber" to pageNum,
        "width" to it.width.toDouble(),
        "height" to it.height.toDouble()
      )
    }
  }

  private fun render(args: HashMap<String, Any>, result: Result) {
    var buffer: ByteBuffer? = null
    var addr = 0L

    val data = renderOnByteBuffer(args) {
      val (a, b) = allocBuffer(it)
      addr = a
      buffer = b
      b
    }

    data?.let {
      it["addr"] = addr
      it["size"] = buffer?.capacity()
      result.success(it)
    } ?: result.error("render_error", "Failed to render PDF page", null)
  }

  private fun renderOnByteBuffer(args: HashMap<String, Any>, createBuffer: (Int) -> ByteBuffer): HashMap<String, Any?>? {
    val docId = args["docId"] as Int
    val page = documents[docId]?.openPage((args["pageNumber"] as Int) - 1) ?: return null

    val w = (args["width"] as? Int ?: page.width)
    val h = (args["height"] as? Int ?: page.height)
    val fullW = (args["fullWidth"] as? Double ?: w.toDouble()).toFloat()
    val fullH = (args["fullHeight"] as? Double ?: h.toDouble()).toFloat()
    val x = args["x"] as? Int ?: 0
    val y = args["y"] as? Int ?: 0
    val fill = args["backgroundFill"] as? Boolean ?: true

    val buffer = createBuffer(w * h * 4)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

    if (fill) bmp.eraseColor(Color.WHITE)

    val mat = Matrix().apply {
      setValues(floatArrayOf(
        fullW / page.width, 0f, -x.toFloat(),
        0f, fullH / page.height, -y.toFloat(),
        0f, 0f, 1f
      ))
    }

    page.render(bmp, null, mat, RENDER_MODE_FOR_PRINT)
    bmp.copyPixelsToBuffer(buffer)
    bmp.recycle()

    return hashMapOf(
      "docId" to docId,
      "pageNumber" to page.index + 1,
      "width" to w,
      "height" to h,
      "fullWidth" to fullW.toDouble(),
      "fullHeight" to fullH.toDouble(),
      "pageWidth" to page.width.toDouble(),
      "pageHeight" to page.height.toDouble()
    )
  }

  private fun allocBuffer(size: Int): Pair<Long, ByteBuffer> {
    val addr = ByteBufferHelper.malloc(size.toLong())
    return addr to ByteBufferHelper.newDirectBuffer(addr, size.toLong())
  }

  private fun releaseBuffer(addr: Long) {
    ByteBufferHelper.free(addr)
  }

  private fun allocTex(): Int {
    val tex = flutterPluginBinding.textureRegistry.createSurfaceTexture()
    val id = tex.id().toInt()
    textures.put(id, tex)
    return id
  }

  private fun releaseTex(id: Int) {
    textures[id]?.release()
    textures.remove(id)
  }

  private fun updateTex(args: HashMap<String, Any>): Int {
    val texId = args["texId"] as Int
    val docId = args["docId"] as Int
    val pageNum = args["pageNumber"] as Int
    val tex = textures[texId] ?: return -8
    val renderer = documents[docId] ?: return -9

    renderer.openPage(pageNum - 1).use { page ->
      val w = args["width"] as? Int ?: page.width
      val h = args["height"] as? Int ?: page.height
      val fullW = (args["fullWidth"] as? Double ?: w.toDouble()).toFloat()
      val fullH = (args["fullHeight"] as? Double ?: h.toDouble()).toFloat()
      val srcX = args["srcX"] as? Int ?: 0
      val srcY = args["srcY"] as? Int ?: 0
      val fill = args["backgroundFill"] as? Boolean ?: true

      if (w <= 0 || h <= 0) return -7

      val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
      if (fill) bmp.eraseColor(Color.WHITE)

      val mat = Matrix().apply {
        setValues(floatArrayOf(
          fullW / page.width, 0f, -srcX.toFloat(),
          0f, fullH / page.height, -srcY.toFloat(),
          0f, 0f, 1f
        ))
      }

      page.render(bmp, null, mat, RENDER_MODE_FOR_PRINT)

      tex.surfaceTexture().setDefaultBufferSize(w, h)
      Surface(tex.surfaceTexture()).use { surface ->
        val canvas = surface.lockCanvas(Rect(0, 0, w, h))
        canvas.drawBitmap(bmp, 0f, 0f, null)
        surface.unlockCanvasAndPost(canvas)
      }

      bmp.recycle()
    }

    return 0
  }
}

fun <R> Surface.use(block: (Surface) -> R): R {
  return try {
    block(this)
  } finally {
    release()
  }
}
