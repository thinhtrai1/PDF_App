package com.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.AttributeSet
import android.view.*
import android.widget.ImageView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.File
import java.net.URL

class PdfRendererView(private val mContext: Context, attrs: AttributeSet?) : RecyclerView(mContext, attrs) {
    private var mListener: StatusCallBack? = null
    private val mAdapter = Adapter(mContext)
    private var mScaleDetector: ScaleGestureDetector? = null
    private var mFilePath: String? = null

    init {
        setHasFixedSize(true)
        adapter = mAdapter
        layoutManager = LinearLayoutManager(mContext)
        mScaleDetector = ScaleGestureDetector(mContext, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector?): Boolean {
                val scale = detector?.scaleFactor ?: return false
                mScaleFactor = 1.0f.coerceAtLeast((mScaleFactor * scale).coerceAtMost(3.0f))
                if (mScaleFactor < 3f) {
                    val centerX = detector.focusX
                    val centerY = detector.focusY
                    var diffX = centerX - mPosX
                    var diffY = centerY - mPosY
                    diffX = diffX * detector.scaleFactor - diffX
                    diffY = diffY * detector.scaleFactor - diffY
                    mPosX -= diffX
                    mPosY -= diffY
                }
                maxWidth = width - width * mScaleFactor
                maxHeight = height - height * mScaleFactor
                invalidate()
                return true
            }
        })
    }

    fun setStatusListener(listener: StatusCallBack) {
        mListener = listener
        mAdapter.setStatusListener(listener)
    }

    fun getFilePath() = mFilePath

    fun rendererUrl(url: String) {
        mListener?.onDownloadProgress(0)
        GlobalScope.launch(Dispatchers.IO) {
            val outputFile = File(mContext.cacheDir, "downloaded_pdf.pdf")
            if (outputFile.exists()) {
                outputFile.delete()
            }
            try {
                val bufferSize = 8192
                val pdfUrl = URL(url)
                val connection = pdfUrl.openConnection().apply { connect() }
                val totalLength = connection.contentLength
                val inputStream = BufferedInputStream(connection.getInputStream(), bufferSize)
                val outputStream = outputFile.outputStream()
                val data = ByteArray(bufferSize)
                var downloaded = 0
                var count: Int
                while (inputStream.read(data).also { count = it } != -1) {
                    outputStream.write(data, 0, count)
                    downloaded += count
                    val progress = (downloaded * 100F / totalLength).toInt()
                    GlobalScope.launch(Dispatchers.Main) {
                        mListener?.onDownloadProgress(if (progress > 100) 100 else progress)
                    }
                }
                outputStream.flush()
                outputStream.close()
                inputStream.close()
            } catch (e: Exception) {
                GlobalScope.launch(Dispatchers.Main) { mListener?.onError(e) }
                return@launch
            }
            GlobalScope.launch(Dispatchers.Main) {
                rendererFile(outputFile)
                mListener?.onDownloadSuccess()
            }
        }
    }

    fun rendererFile(file: File) {
        mFilePath = file.path
        mAdapter.rendererFile(file)
    }

    fun closePdfRender() {
        mAdapter.closeRender()
    }

    private class Adapter(private val mContext: Context) : RecyclerView.Adapter<Adapter.ViewHolder>() {
        private var mPdfRenderer: PdfRenderer? = null
        private var mListener: StatusCallBack? = null
        private val mSavedBitmap = HashMap<Int, Bitmap>()

        fun rendererFile(file: File) {
            mPdfRenderer = PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
            mSavedBitmap.clear()
            notifyDataSetChanged()
        }

        fun closeRender() {
            mPdfRenderer?.close()
        }

        fun setStatusListener(listener: StatusCallBack) {
            mListener = listener
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(LayoutInflater.from(mContext).inflate(R.layout.item_rcv_pdf_page, parent, false))
        }

        override fun getItemCount(): Int {
            return mPdfRenderer?.pageCount ?: 0
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            with(holder.view) {
                visibility = View.GONE
                renderPage(position) {
                    visibility = View.VISIBLE
                    findViewById<ImageView>(R.id.imvPage).apply {
                        adjustViewBounds = true
                        setImageBitmap(it)
                    }
                    if (position == 0) {
                        mListener?.onDisplay()
                    }
                }
            }
        }

        private fun renderPage(pageNo: Int, ratio: Int = 1, onBitmap: (Bitmap?) -> Unit) {
            GlobalScope.launch(Dispatchers.IO) {
                synchronized(mPdfRenderer!!) {
                    mSavedBitmap[pageNo]?.let {
                        onBitmap(it)
                        return@launch
                    }
                    mPdfRenderer!!.openPage(pageNo).apply {
                        val bitmap = Bitmap.createBitmap(width * ratio, height * ratio, Bitmap.Config.ARGB_8888)
                        render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        close()
                        mSavedBitmap[pageNo] = bitmap
                        GlobalScope.launch(Dispatchers.Main) { onBitmap(bitmap) }
                    }
                }
            }
        }

        private class ViewHolder(val view: View) : RecyclerView.ViewHolder(view)
    }

    private var mActivePointerId = -1
    private var mScaleFactor = 1f
    private var maxWidth = 0.0f
    private var maxHeight = 0.0f
    private var mLastTouchX = 0f
    private var mLastTouchY = 0f
    private var mPosX = 0f
    private var mPosY = 0f
    private var width = 0f
    private var height = 0f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        width = MeasureSpec.getSize(widthMeasureSpec).toFloat()
        height = MeasureSpec.getSize(heightMeasureSpec).toFloat()
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        super.onTouchEvent(ev)
        val action = ev.action
        mScaleDetector!!.onTouchEvent(ev)
        when (action and MotionEvent.ACTION_MASK) {
            MotionEvent.ACTION_DOWN -> {
                val x = ev.x
                val y = ev.y
                mLastTouchX = x
                mLastTouchY = y
                mActivePointerId = ev.getPointerId(0)
            }
            MotionEvent.ACTION_MOVE -> {
                val pointerIndex = (action and MotionEvent.ACTION_POINTER_INDEX_MASK
                        shr MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                val x = ev.getX(pointerIndex)
                val y = ev.getY(pointerIndex)
                val dx = x - mLastTouchX
                val dy = y - mLastTouchY
                mPosX += dx
                mPosY += dy
                if (mPosX > 0.0f) mPosX = 0.0f
                else if (mPosX < maxWidth) mPosX = maxWidth
                if (mPosY > 0.0f) mPosY = 0.0f
                else if (mPosY < maxHeight) mPosY = maxHeight
                mLastTouchX = x
                mLastTouchY = y
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                mActivePointerId = -1
            }
            MotionEvent.ACTION_CANCEL -> {
                mActivePointerId = -1
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val pointerIndex =
                    action and MotionEvent.ACTION_POINTER_INDEX_MASK shr MotionEvent.ACTION_POINTER_INDEX_SHIFT
                val pointerId = ev.getPointerId(pointerIndex)
                if (pointerId == mActivePointerId) {
                    val newPointerIndex = if (pointerIndex == 0) 1 else 0
                    mLastTouchX = ev.getX(newPointerIndex)
                    mLastTouchY = ev.getY(newPointerIndex)
                    mActivePointerId = ev.getPointerId(newPointerIndex)
                }
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.translate(mPosX, mPosY)
        canvas.scale(mScaleFactor, mScaleFactor)
        canvas.restore()
    }

    override fun dispatchDraw(canvas: Canvas) {
        canvas.save()
        if (mScaleFactor == 1.0f) {
            mPosX = 0.0f
            mPosY = 0.0f
        }
        canvas.translate(mPosX, mPosY)
        canvas.scale(mScaleFactor, mScaleFactor)
        super.dispatchDraw(canvas)
        canvas.restore()
        invalidate()
    }

    interface StatusCallBack {
        fun onDownloadProgress(progress: Int) {}
        fun onDownloadSuccess() {}
        fun onDisplay() {}
        fun onError(error: Throwable) {}
    }
}