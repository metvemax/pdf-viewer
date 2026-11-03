package com.example.pdfviewer

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

class MainActivity : Activity() {

    // Все обращения к PdfRenderer идут через один поток — так требует Android API.
    private val executor = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    @Volatile private var renderer: PdfRenderer? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var generation = 0
    private var currentUri: Uri? = null
    private var docName: String = ""

    private lateinit var recycler: RecyclerView
    private lateinit var emptyBox: View
    private lateinit var hint: TextView
    private val adapter = PageAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recycler = findViewById(R.id.pages)
        emptyBox = findViewById(R.id.emptyBox)
        hint = findViewById(R.id.hint)
        findViewById<View>(R.id.openButton).setOnClickListener { pickPdf() }

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) = updateTitle()
        })

        val saved = savedInstanceState?.getString(KEY_URI)?.let { Uri.parse(it) }
        val fromIntent = if (intent?.action == Intent.ACTION_VIEW) intent.data else null
        (saved ?: fromIntent)?.let { openPdf(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        currentUri?.let { outState.putString(KEY_URI, it.toString()) }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_OPEN, 0, R.string.open).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == MENU_OPEN) { pickPdf(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun pickPdf() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_PICK)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK && resultCode == RESULT_OK) data?.data?.let { openPdf(it) }
    }

    private fun openPdf(uri: Uri) {
        val gen = ++generation
        currentUri = uri
        docName = queryName(uri)
        hint.setText(R.string.loading)
        findViewById<View>(R.id.openButton).visibility = View.GONE
        emptyBox.visibility = View.VISIBLE

        executor.execute {
            var pfd: ParcelFileDescriptor? = null
            var r: PdfRenderer? = null
            try {
                pfd = contentResolver.openFileDescriptor(uri, "r") ?: error("no fd")
                r = PdfRenderer(pfd)
                val sizes = List(r.pageCount) { i ->
                    val p = r.openPage(i)
                    try { p.width to p.height } finally { p.close() }
                }
                val newR = r
                val newPfd = pfd
                ui.post {
                    if (gen != generation) {
                        executor.execute { closeQuietly(newR, newPfd) }
                        return@post
                    }
                    val oldR = renderer
                    val oldPfd = descriptor
                    renderer = newR
                    descriptor = newPfd
                    adapter.setDocument(newR, sizes)
                    recycler.scrollToPosition(0)
                    emptyBox.visibility = View.GONE
                    updateTitle()
                    // Закрываем старый документ в том же потоке, где идёт рендеринг.
                    executor.execute { closeQuietly(oldR, oldPfd) }
                }
            } catch (e: Exception) {
                closeQuietly(r, pfd)
                ui.post {
                    if (gen != generation) return@post
                    hint.setText(R.string.error)
                    findViewById<View>(R.id.openButton).visibility = View.VISIBLE
                    Toast.makeText(this, R.string.error, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun updateTitle() {
        val total = adapter.itemCount
        if (total == 0) { title = getString(R.string.app_name); return }
        val first = (recycler.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
            .coerceAtLeast(0)
        title = "$docName — " + getString(R.string.page_of, first + 1, total)
    }

    private fun queryName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) return c.getString(0) ?: ""
            }
        } catch (_: Exception) { }
        return uri.lastPathSegment ?: ""
    }

    private fun closeQuietly(r: PdfRenderer?, pfd: ParcelFileDescriptor?) {
        try { r?.close() } catch (_: Exception) { }
        try { pfd?.close() } catch (_: Exception) { }
    }

    override fun onDestroy() {
        super.onDestroy()
        val r = renderer
        val pfd = descriptor
        renderer = null
        descriptor = null
        executor.execute { closeQuietly(r, pfd) }
        executor.shutdown()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------- Список страниц ----------

    private inner class PageHolder(val image: ImageView) : RecyclerView.ViewHolder(image) {
        @Volatile var boundPos = -1
    }

    private inner class PageAdapter : RecyclerView.Adapter<PageHolder>() {
        private var sizes: List<Pair<Int, Int>> = emptyList()
        private var doc: PdfRenderer? = null

        fun setDocument(r: PdfRenderer, s: List<Pair<Int, Int>>) {
            doc = r
            sizes = s
            notifyDataSetChanged()
        }

        override fun getItemCount() = sizes.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val iv = ImageView(parent.context).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                setBackgroundColor(Color.WHITE)
            }
            return PageHolder(iv)
        }

        override fun onBindViewHolder(h: PageHolder, position: Int) {
            val (pw, ph) = sizes[position]
            val w = if (recycler.width > 0) recycler.width else resources.displayMetrics.widthPixels
            val hgt = (w.toFloat() * ph / pw).toInt().coerceAtLeast(1)

            h.image.layoutParams = RecyclerView.LayoutParams(w, hgt).apply { bottomMargin = dp(8) }
            h.image.setImageDrawable(null)
            h.boundPos = position

            val r = doc ?: return
            executor.execute {
                if (h.boundPos != position || r !== renderer) return@execute
                try {
                    val page = r.openPage(position)
                    val bmp = Bitmap.createBitmap(w, hgt, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()
                    ui.post { if (h.boundPos == position) h.image.setImageBitmap(bmp) }
                } catch (_: Exception) { }
            }
        }

        override fun onViewRecycled(h: PageHolder) {
            h.boundPos = -1
            h.image.setImageDrawable(null)
        }
    }

    companion object {
        private const val REQ_PICK = 1
        private const val MENU_OPEN = 1
        private const val KEY_URI = "uri"
    }
}
