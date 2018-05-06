package com.kendovivi.mediapicker

import android.database.Cursor
import android.graphics.PorterDuff
import android.os.Bundle
import android.provider.MediaStore
import android.support.v4.app.Fragment
import android.support.v4.app.LoaderManager
import android.support.v4.content.CursorLoader
import android.support.v4.content.Loader
import android.support.v7.widget.LinearLayoutManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.GridView
import android.widget.ImageView
import kotlinx.android.synthetic.main.fragment_gallery.*

class MediaPickerFragment : Fragment(), LoaderManager.LoaderCallbacks<Cursor> {


    companion object {

        // タブメニューインデックス
        val galleryIndex = 0
        val photoIndex = 1
        val videoIndex = 2

        val TabIndexKey = "TabIndexKey"

        fun newInstance(tabIndex: Int): MediaPickerFragment {
            val fragment = MediaPickerFragment()
            val bundle = Bundle()
            bundle.putInt(TabIndexKey, tabIndex)
            fragment.arguments = bundle
            return fragment
        }
    }

    private lateinit var rootView: View

    private var tabIndex: Int = 0
    private var gridView: GridView? = null
    private var adapter: MediaPickerGridViewAdapter? = null
    private var layoutManager: LinearLayoutManager? = null
    private var headerViews: List<View> = arrayListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tabIndex = arguments!!.getInt(TabIndexKey)


        loaderManager.initLoader(0, null, this)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        rootView = inflater.inflate(R.layout.fragment_gallery, container, false)
        layoutManager = LinearLayoutManager(context)
        gridView = rootView.findViewById(R.id.gallery_grid_view)

        adapter = MediaPickerGridViewAdapter(context!!, null)
        gridView?.adapter = adapter
        adapter?.notifyDataSetChanged()

        gridView?.onItemClickListener = object : AdapterView.OnItemClickListener{

            override fun onItemClick(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {

            }
        }

        gridView?.onItemLongClickListener = object : AdapterView.OnItemLongClickListener{
            override fun onItemLongClick(parent: AdapterView<*>?, view: View?, position: Int, id: Long): Boolean {
                var image: ImageView = view?.findViewById(R.id.gallery_item_view) as ImageView

                image.drawable.setColorFilter(0x77000000, PorterDuff.Mode.SRC_ATOP)
                image.invalidate()

                return false
            }
        }

        return rootView
    }

    override fun onCreateLoader(id: Int, args: Bundle?): Loader<Cursor> {
        val projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DATA,
                MediaStore.Files.FileColumns.DATE_ADDED,
                MediaStore.Files.FileColumns.MEDIA_TYPE,
                MediaStore.Files.FileColumns.MIME_TYPE,
                MediaStore.Files.FileColumns.TITLE,
                MediaStore.Video.VideoColumns.DURATION
        )

        val selection = (MediaStore.Files.FileColumns.MEDIA_TYPE + "="
                + MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
                + " OR "
                + MediaStore.Files.FileColumns.MEDIA_TYPE + "="
                + MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO)

        val queryUri = MediaStore.Files.getContentUri("external")

        return CursorLoader(
                context!!,
                queryUri,
                projection,
                selection,
                null,
                MediaStore.Files.FileColumns.DATE_ADDED + " DESC"
        )
    }

    override fun onLoadFinished(loader: Loader<Cursor>, cursor: Cursor?) {
        adapter?.swapCursor(cursor)
        adapter?.notifyDataSetChanged()
    }

    override fun onLoaderReset(loader: Loader<Cursor>) {
        adapter?.swapCursor(null)
        adapter?.notifyDataSetChanged()
    }

}