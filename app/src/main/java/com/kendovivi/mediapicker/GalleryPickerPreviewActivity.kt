package jp.timebank.post

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.provider.MediaStore
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import jp.timebank.R
import jp.timebank.common.BaseActivity
import kotlinx.android.synthetic.main.activity_gallery_picker_preview.*
import kotlinx.android.synthetic.main.list_item_gallery_picker_preview.view.*
import java.util.ArrayList
import android.graphics.Bitmap
import android.graphics.PorterDuff
import android.widget.AdapterView
import android.widget.ArrayAdapter
import jp.timebank.webapi.WebApi
import java.io.File


class GalleryPickerPreviewActivity : BaseActivity(), View.OnClickListener {

    companion object {
        private val LogTag = "GalleryPickerPreviewActivity"

        val answerTypeAll = "every" // 全員に公開
        val answerTypeTimeOwner = "owner" // タイムオーナーに公開
        val answerTypePaidUser = "pay" // タイムを使用した人に公開

        val kindText = 0
        val kindVoice = 10
        val kindImage = 20
        val kindMovie = 30
    }

    lateinit var releaseTargetNamesAdapter: ArrayAdapter<String>

    var answerType = answerTypeAll

    private var isEditMode: Boolean = false // true: 編集する, false: 回答する
    private var isSingleFile: Boolean = false // true: 1ファイル, false: 複数ファイル

    private var layoutManager: LinearLayoutManager? = null
    private var selectedMediaList: ArrayList<GalleryPickerFragment.MyMedia>? = null

    var adapter: PreviewAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery_picker_preview)
        layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        preview_recycler_view.layoutManager = layoutManager

        selectedMediaList = intent.getSerializableExtra(GalleryPickerFragment.SelectedMediaKey) as ArrayList<GalleryPickerFragment.MyMedia>
        isSingleFile = selectedMediaList?.size == 1
        adapter = PreviewAdapter(this, selectedMediaList!!)

        preview_recycler_view.adapter = adapter
        adapter?.notifyDataSetChanged()



        if (isSingleFile) {
            layout_release_target.visibility = View.INVISIBLE
            layout_pay_second.visibility = View.INVISIBLE

            hint_text.text = "一つの動画及び写真の閲覧を制限することはできない。"
        } else {
            layout_release_target.visibility = View.VISIBLE
            layout_pay_second.visibility = View.VISIBLE

            hint_text.text = "以下の公開設定をすると、2枚目以降の動画及び写真の閲覧を制限できます。"

            val releaseTargetNames: MutableList<String> = mutableListOf(
                    "全員に公開",
                    "タイムオーナーに公開",
                    "タイムを使用した人に公開"
            )
            releaseTargetNamesAdapter = ArrayAdapter(this, R.layout.spinner_item_black_text_color, releaseTargetNames)
            releaseTargetNamesAdapter.setDropDownViewResource(R.layout.support_simple_spinner_dropdown_item)
            spinner_release_target.isFocusable = false
            spinner_release_target.adapter = releaseTargetNamesAdapter
            spinner_release_target.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) {
                }

                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val answerTypes = arrayOf(answerTypeAll, answerTypeTimeOwner, answerTypePaidUser)
                    answerType = answerTypes[position]

                    when (answerType) {
                        answerTypePaidUser -> { // タイムを使用した人に公開
                            if (isEditMode) { // 編集モード
                                // 消費秒数をdisable状態にする
                                layout_pay_second.alpha = 0.5f
                                btn_minus.isEnabled = false
                                btn_plus.isEnabled = false
                                edit_text_second.isEnabled = false

                                if (adapter?.isHide!!) {
                                    adapter?.isHide = false
                                    adapter?.notifyDataSetChanged()
                                }
                            } else {
                                // 消費秒数をenable状態にする
                                layout_pay_second.alpha = 1f
                                btn_minus.isEnabled = true
                                btn_plus.isEnabled = true
                                edit_text_second.isEnabled = true

                                // 2枚目以降の写真をグレーアウト
                                adapter?.isHide = true
                                adapter?.notifyDataSetChanged()

                            }
                        }
                        else -> { // 全員に公開、タイムオーナーに公開
                            // 消費秒数をdisable状態にする
                            layout_pay_second.alpha = 0.5f
                            btn_minus.isEnabled = false
                            btn_plus.isEnabled = false
                            edit_text_second.isEnabled = false

                            if (adapter?.isHide!!) {
                                adapter?.isHide = false
                                adapter?.notifyDataSetChanged()
                            }
                        }
                    }

                    // 初回自動で呼ばれるものはスルー
                    if (spinner_release_target.isFocusable == false) {
                        spinner_release_target.isFocusable = true
                        return
                    }
                }
            }
        }

        btn_next.setOnClickListener(this)
    }

    fun createMediaPart() {

        var partList = ArrayList<MultiPart>()
        var part = MultiPart(null, type = 10, fileName = selectedMediaList!![0].mPath)
        partList.add(part)
        WebApi.mediaUpload(partList) {
            result, error -> {

        }

        }
    }

    override fun onClick(v: View?) {
        when(v?.id) {
            R.id.btn_next -> {
                var intent = Intent(this, GalleryPickerFormActivity::class.java)
                startActivity(intent)
            }
        }
    }

    class PreviewAdapter(val context: Context, val selectedMediaList: ArrayList<GalleryPickerFragment.MyMedia>) : RecyclerView.Adapter<PreviewAdapter.ViewHolder>() {

        var isHide: Boolean = false // true 2枚目以降をグレーアウト

        private var recyclerView: RecyclerView? = null

        override fun onAttachedToRecyclerView(recyclerView: RecyclerView?) {
            this.recyclerView = recyclerView
        }

        override fun onCreateViewHolder(parent: ViewGroup?, viewType: Int): ViewHolder {
            val view: View

            view = LayoutInflater.from(parent?.context)
                    .inflate(R.layout.list_item_gallery_picker_preview, parent, false)

            return ViewHolder(view)
        }

        override fun getItemCount(): Int {
            return selectedMediaList.size
        }

        override fun onBindViewHolder(holder: ViewHolder?, position: Int) {
            holder?.setUp(context, selectedMediaList[position], isHide)
        }

        class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

            fun setUp(context: Context, myMedia: GalleryPickerFragment.MyMedia, isHide: Boolean) {

                itemView.layout_preview_image_grayout_hint.visibility = View.INVISIBLE

                when (myMedia.mType) {
                    MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> {
                        var bmOptions = BitmapFactory.Options()
                        bmOptions.inSampleSize = 5

                        val density = context.resources.displayMetrics.density

                        var bitmap = BitmapFactory.decodeFile(myMedia.mPath, bmOptions)
                        bitmap = Bitmap.createScaledBitmap(bitmap, (300 * density + 0.5).toInt(), (300 * density + 0.5).toInt(), true)
                        itemView.preview_image.visibility = View.VISIBLE
                        itemView.preview_video.visibility = View.GONE
                        itemView.preview_image.setImageBitmap(bitmap)

                        if (adapterPosition != 0 && isHide) {
                            itemView.layout_preview_image_grayout_hint.visibility = View.VISIBLE
                            itemView.preview_image.drawable.setColorFilter(0x77000000, PorterDuff.Mode.SRC_ATOP)
                            itemView.preview_image.invalidate()
                        }

                    }
                    MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> {
                        itemView.preview_image.visibility = View.GONE
                        itemView.preview_video.visibility = View.VISIBLE
                        itemView.preview_video.setVideoPath(myMedia.mPath)
                        itemView.preview_video.stopPlayback()
                        itemView.preview_video.start()

                        if (adapterPosition != 0 && isHide) {
                            itemView.layout_preview_image_grayout_hint.visibility = View.VISIBLE
                        }
                    }
                }
            }
        }
    }

    class MultiPart(content: File?, priority: Int? = 1, type: Int, fileName: String?) {

        var content: File? = content
        var priority: Int? = priority
        var type: Int? = type
        var fileName: String? = fileName



    }
}