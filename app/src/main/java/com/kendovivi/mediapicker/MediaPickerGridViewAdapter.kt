package com.kendovivi.mediapicker

import android.content.ContentResolver
import android.content.Context
import android.content.res.Resources
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.AsyncTask
import android.os.Handler
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CursorAdapter
import android.widget.ImageView
import android.widget.TextView
import com.kendovivi.mediapicker.R.id.*
import java.util.concurrent.TimeUnit
import java.lang.ref.WeakReference
import android.graphics.drawable.BitmapDrawable
import android.os.AsyncTask.execute
import com.kendovivi.mediapicker.MediaPickerGridViewAdapter.AsyncDrawable
import android.graphics.drawable.Drawable
import android.R.string.cancel










class MediaPickerGridViewAdapter(context: Context, cursor: Cursor? = null) : CursorAdapter(context, cursor) {



    private var layoutInlater : LayoutInflater? = null
    private var handler = Handler()

    init {
        layoutInlater = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
    }

    override fun newView(context: Context?, cursor: Cursor?, parent: ViewGroup?): View {
        val view = layoutInlater!!.inflate(R.layout.item_gallery, parent, false)
        val holder = ViewHolder()
        holder.image = view.findViewById(R.id.gallery_item_view)
        holder.duration = view.findViewById(R.id.video_duration_text)

        view.tag = holder

        return view

    }

    override fun bindView(view: View?, context: Context?, cursor: Cursor?) {
        val holder: ViewHolder = view?.getTag() as ViewHolder
        holder.duration?.visibility = View.INVISIBLE

        val id = cursor?.getInt(cursor?.getColumnIndex(MediaStore.Files.FileColumns._ID))
//        if (id == 2670) {
//            return
//        }
        val type = cursor?.getInt(cursor.getColumnIndex(MediaStore.Files.FileColumns.MEDIA_TYPE))

        val opt = BitmapFactory.Options()
        opt.inSampleSize = 1

        when(type) {
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> {
//                ThumbnailWorkerTask(context!!.contentResolver, handler,  MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE, holder.image!!).execute(id!!.toLong())
                loadBitmap(context!!, MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE, id!!.toLong(), holder.image!!, BitmapFactory.decodeResource(context.resources, R.drawable.ic_launcher))
            }
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> {
                val duration = cursor.getLong(cursor.getColumnIndex(MediaStore.Video.VideoColumns.DURATION))
//                ThumbnailWorkerTask(context!!.contentResolver, handler,  MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO, holder.image!!).execute(id!!.toLong())
                loadBitmap(context!!, MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO, id!!.toLong(), holder.image!!, BitmapFactory.decodeResource(context.resources, R.drawable.ic_launcher))

                holder.duration?.visibility = View.VISIBLE
                holder.duration?.setText(formatMillSeconds(duration))
            }
        }

    }

    fun formatMillSeconds(millis: Long): String {
        return String.format("%02d:%02d:%02d",
                TimeUnit.MILLISECONDS.toHours(millis),
                TimeUnit.MILLISECONDS.toMinutes(millis) -
                        TimeUnit.HOURS.toMinutes(TimeUnit.MILLISECONDS.toHours(millis)), // The change is in this line
                TimeUnit.MILLISECONDS.toSeconds(millis) -
                        TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(millis)));
    }


    class ViewHolder {
        var image: ImageView? = null
        var duration: TextView? = null
    }

//    class ThumbnailWorkerTask(var contentResolver: ContentResolver,var handler: Handler, var type: Int,var imageView: ImageView) : AsyncTask<Long, Void, Pair<Long,Bitmap>>(){
//
//        override fun onPreExecute() {
//            imageView.setTag(this)
//        }
//
//        private fun isTargetChanged(): Boolean {
//            return imageView.getTag() != this
//        }
//
//        override fun doInBackground(vararg longs: Long?): Pair<Long, Bitmap>? {
//
//            if (isTargetChanged()) {
//                Log.d("wangzheng", "do in background: target changed")
//                return null
//            }
//
//            var id = longs[0]
//
//            when(type) {
//                MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> {
//                    return Pair<Long, Bitmap>(id!!, MediaStore.Images.Thumbnails.getThumbnail(
//                            contentResolver,
//                            id!!,
//                            MediaStore.Images.Thumbnails.MICRO_KIND,
//                            null))
//                }
//                else -> {
//                     var bitmap = MediaStore.Video.Thumbnails.getThumbnail(
//                            contentResolver,
//                            id!!,
//                            MediaStore.Video.Thumbnails.MICRO_KIND,
//                            null)
//
//                    if (bitmap == null) {
//                        Log.d("wangzheng","bitmap null + id=" + id)
//                    }
//
//                    return Pair<Long, Bitmap>(id!!, bitmap)
//                }
//            }
//
//        }
//
//        override fun onPostExecute(result: Pair<Long, Bitmap>?) {
//            if (result == null || result.second == null) {
//                return
//            }
//
//            if (isTargetChanged()) {
//                return
//            }
//
//            val runnable = Runnable { imageView.setImageBitmap(result?.second) }
//            handler.post(runnable)
//        }



    companion object {
        fun loadBitmap(context: Context,type: Int, id: Long, imageView: ImageView, loadingBitmap: Bitmap) {
            if (cancelPotentialWork(id, imageView)) {
                val task = ThumbnailWorkerSecondTask(context!!.contentResolver, type, imageView)

                val asyncDrawable = AsyncDrawable(context.resources, loadingBitmap, task)
                imageView.setImageDrawable(asyncDrawable)

                task.execute(id)
            }
        }

        fun cancelPotentialWork(id: Long, imageView: ImageView): Boolean {
            val bitmapWorkerTask = getThumbnailWorkerSecondTask(imageView)

            if (bitmapWorkerTask != null) {
                val bitmapData = bitmapWorkerTask!!.id
                if (bitmapData != id) {
                    // 以前のタスクをキャンセル
                    bitmapWorkerTask!!.cancel(true)
                } else {
                    // 同じタスクがすでに走っているので、このタスクは実行しない
                    return false
                }
            }
            // この ImageView に関連する新しいタスクを実行する
            return true
        }

        fun getThumbnailWorkerSecondTask(imageView: ImageView?): ThumbnailWorkerSecondTask? {
            if (imageView != null) {
                val drawable = imageView.drawable
                if (drawable is AsyncDrawable) {
                    return drawable.thumbnailWorkerSecondTask
                }
            }
            return null
        }
    }



    class ThumbnailWorkerSecondTask(var contentResolver: ContentResolver, var type: Int,var imageView: ImageView) : AsyncTask<Long, Void, Bitmap>(){

        private val imageViewReference: WeakReference<ImageView>? = WeakReference(imageView)
        var id: Long? = null

        override fun doInBackground(vararg longs: Long?): Bitmap? {

            id = longs[0]

            when(type) {
                MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> {
                    return MediaStore.Images.Thumbnails.getThumbnail(
                            contentResolver,
                            id!!,
                            MediaStore.Images.Thumbnails.MICRO_KIND,
                            null)
                }
                else -> {
                    var bitmap = MediaStore.Video.Thumbnails.getThumbnail(
                            contentResolver,
                            id!!,
                            MediaStore.Video.Thumbnails.MICRO_KIND,
                            null)

                    if (bitmap == null) {
                        Log.d("wangzheng","bitmap null + id=" + id)
                    }

                    return bitmap
                }
            }

        }

        override fun onPostExecute(bitmap: Bitmap?) {
            var bitmap = bitmap
            // キャンセルされていたらなにもしない
            if (isCancelled) {
                bitmap = null
            }

            if (imageViewReference != null && bitmap != null) {
                val imageView = imageViewReference.get()
                if (imageView != null) {
                    // ImageView からタスクを取り出す
                    val bitmapWorkerTask = getThumbnailWorkerSecondTask(imageView)
                    if (this === bitmapWorkerTask && imageView != null) {
                        // 同じタスクなら ImageView に Bitmap をセット
                        imageView!!.setImageBitmap(bitmap)
                    }
                }
            }
        }






    }

    internal class AsyncDrawable(res: Resources, bitmap: Bitmap, thumbnailWorkerSecondTask: ThumbnailWorkerSecondTask) : BitmapDrawable(res, bitmap) {
        private val bitmapWorkerTaskReference: WeakReference<ThumbnailWorkerSecondTask>

        val thumbnailWorkerSecondTask: ThumbnailWorkerSecondTask?
            get() = bitmapWorkerTaskReference.get()

        init {
            bitmapWorkerTaskReference = WeakReference<ThumbnailWorkerSecondTask>(thumbnailWorkerSecondTask)
        }
    }





}