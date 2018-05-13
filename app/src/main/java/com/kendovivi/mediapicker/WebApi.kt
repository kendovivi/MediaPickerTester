package jp.timebank.webapi

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Uri
import android.os.*
import android.preference.PreferenceManager
import android.support.v4.util.LruCache
import android.telephony.TelephonyManager
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import com.android.volley.*
import com.android.volley.toolbox.*
import com.google.firebase.dynamiclinks.DynamicLink
import com.google.firebase.dynamiclinks.FirebaseDynamicLinks
import com.google.gson.JsonObject
import com.rollbar.android.Rollbar
import jp.timebank.BuildConfig.BASE_URL
import jp.timebank.BuildConfig.MEDIA_UPLOAD_URL
import jp.timebank.application.TimebankApplication
import jp.timebank.common.TLSSocketFactory
import jp.timebank.post.GalleryPickerPreviewActivity
import org.apache.http.HttpEntity
import org.apache.http.entity.mime.HttpMultipartMode
import org.apache.http.entity.mime.MultipartEntityBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URL
import java.security.KeyManagementException
import java.security.NoSuchAlgorithmException
import java.util.*
import kotlin.collections.ArrayList

sealed class ApiError: Exception() {
    class Offline : ApiError()
    class Url: ApiError()
    class Http(val status: Int): ApiError()
    class DataNil: ApiError()
    class Json(val url: String? = null, val status: Int? = null, val response: String? = null): ApiError()
    class Facebook: ApiError()
    class LoginToken(val messages: JSONArray): ApiError()
    class Validation(val messages: JSONArray): ApiError()
    class OAuthAccessToken(val messages: JSONArray): ApiError()
    class Login(val messages: JSONArray): ApiError() // ログイン認証(JWT)エラー
    class Order(val messages: JSONArray): ApiError() // 注文エラー
    class Exercise(val messages: JSONArray): ApiError() // 利用エラー
    class SignIn(val messages: JSONArray): ApiError() // サインインエラー
    class ExistUser(val messages: JSONArray): ApiError() // すでに登録済みのユーザーエラー
    class ExistOAuth(val messages: JSONArray): ApiError() // OAuth登録済みのユーザーエラー
    class ExistUserNoOAuth(val messages: JSONArray): ApiError() // メールアドレスは存在していて、OAuthに登録されていないユーザー(providerを間違えているなど)
    class Maintenance: ApiError, Parcelable { // メンテナンス(1:ダイアログ、2:ダイアログと詳細ボタン、3:WebView)

        companion object {

            @JvmField
            val CREATOR: Parcelable.Creator<Maintenance> = object : Parcelable.Creator<Maintenance> {
                override fun createFromParcel(source: Parcel): Maintenance {
                    return Maintenance(source)
                }

                override fun newArray(size: Int): Array<Maintenance?> {
                    return arrayOfNulls(size)
                }
            }
        }

        var type: Int
        var messages: JSONArray
        var url: String

        constructor(type: Int, messages: JSONArray, url: String): super() {
            this.type = type
            this.messages = messages
            this.url = url
        }

        constructor(source: Parcel): super() {
            val messages = ArrayList<String>()
            this.type = source.readInt()
            this.messages = JSONArray()
            source.readStringList(messages)
            messages.forEach { message ->
                this.messages.put(message)
            }
            this.url = source.readString()
        }

        override fun writeToParcel(dest: Parcel?, flags: Int) {
            dest?.writeInt(this.type)
            val messages = ArrayList<String>()
            for (i in (0 until this.messages.length())) {
                messages.add(this.messages[i] as String)
            }
            dest?.writeStringList(messages)
            dest?.writeString(this.url)
        }

        override fun describeContents(): Int = 0
    }
    class Unexpected(val messages: JSONArray): ApiError() // 想定外のエラー
    class Other(val messages: JSONArray): ApiError() // その他
}

// WebApiシングルトン
object WebApi {

    private val LogTag = "WebApi"
    public val JwtKey = "jwt"

    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = maxMemory / 8
    private val imageCache = LruCache<String, Bitmap>(cacheSize)
    private val networkTimeout = 60000 // 60秒(60000ミリ秒)
    private val networkRetryCount = DefaultRetryPolicy.DEFAULT_MAX_RETRIES // 1回
    private val networkBackoffMult = DefaultRetryPolicy.DEFAULT_BACKOFF_MULT // 1.0

    var featuredInterval = 10.0
    var featuredAutoReloadInterval = 3600.0
    var priceAutoReloadInterval = 60.0
    var categories: JSONArray = JSONArray()
    var preTalentsCategories: JSONArray = JSONArray() // 投票所カテゴリー
    var defectReportUrl: String? = null
    var faqUrl: String? = null
    var userTermsUrl: String? = null
    var glossaryUrl: String? = null
    var privacyUrl: String? = null
    var reviewUrl: String? = null
    var ownerTermsUrl: String? = null
    var guideUrl: String? = null
    var supportUrl: String? = null
    var noticeUrl: String? = null
    var androidLatestVersion: String? = null

    private val context: Context
        get() = TimebankApplication.applicationContext
    private val requestQueue = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.KITKAT) {
        var stack: HttpStack? = null
        try {
            stack = HurlStack(null, TLSSocketFactory())
        } catch (e: KeyManagementException) {
            Log.d(LogTag, "Could not create new stack for TLS v1.2");
            stack = HurlStack()
        } catch (e: NoSuchAlgorithmException) {
            Log.d(LogTag, "Could not create new stack for TLS v1.2");
            stack = HurlStack()
        }
        Volley.newRequestQueue(context, stack)
    } else {
        Volley.newRequestQueue(context)
    }

    private var statusCode: Int = 200

    var targetActivity: Activity? = null

    // 10
    // 設定情報
    fun setting(completionHandler: (error: Exception?) -> Unit) {
        val url = BASE_URL + "/setting"

        this.get(url = url, completionHandler = { result: JSONObject?, error: Exception? ->
            if (error == null) {

                // 設定情報はstaticに保持
                if (result != null) {

                    val featuredInterval = try { result.getDouble("featured_interval") } catch (e: Exception) { null }
                    featuredInterval?.let {
                        this.featuredInterval = it
                    }
                    val featuredAutoReloadInterval = try { result.getDouble("featured_auto_reload_interval") } catch (e: Exception) { null }
                    featuredAutoReloadInterval?.let {
                        this.featuredAutoReloadInterval = it
                    }
                    val priceAutoReloadInterval = try { result.getDouble("price_auto_reload_interval") } catch (e: Exception) { null }
                    priceAutoReloadInterval?.let {
                        this.priceAutoReloadInterval = it
                    }
                    val categories = try { result.getJSONArray("categories") } catch (e: Exception) { null }
                    categories?.let {
                        this.categories = categories
                    }
                    val preTalentsCategories = try { result.getJSONArray("pre_talent_categories") } catch (e: Exception) { null }
                    preTalentsCategories?.let {
                        this.preTalentsCategories = preTalentsCategories
                    }
                    val defectReportUrl = try { result.getString("defect_report_url") } catch (e: Exception) { null }
                    defectReportUrl?.let {
                        this.defectReportUrl = it
                    }
                    val faqUrl = try { result.getString("faq_url") } catch (e: Exception) { null }
                    faqUrl?.let {
                        this.faqUrl = it
                    }
                    val userTermsUrl = try { result.getString("user_terms_url") } catch (e: Exception) { null }
                    userTermsUrl?.let {
                        this.userTermsUrl = it
                    }
                    val glossaryUrl = try { result.getString("glossary_url") } catch (e: Exception) { null }
                    glossaryUrl?.let {
                        this.glossaryUrl = it
                    }
                    val privacyUrl = try { result.getString("privacy_url") } catch (e: Exception) { null }
                    privacyUrl?.let {
                        this.privacyUrl = it
                    }
                    val reviewUrl = try { result.getString("review_url") } catch (e: Exception) { null }
                    reviewUrl?.let {
                        this.reviewUrl = it
                    }
                    val ownerTermsUrl = try { result.getString("owner_terms_url") } catch (e: Exception) { null }
                    ownerTermsUrl?.let {
                        this.ownerTermsUrl = it
                    }
                    val guideUrl = try { result.getString("guide_url") } catch (e: Exception) { null }
                    guideUrl?.let {
                        this.guideUrl = it
                    }
                    val supportUrl = try { result.getString("support_url") } catch (e: Exception) { null }
                    supportUrl?.let {
                        this.supportUrl = it
                    }
                    val noticeUrl = try { result.getString("notice_url") } catch (e: Exception) { null }
                    noticeUrl?.let {
                        this.noticeUrl = it
                    }
                    val androidLatestVersion = try { result.getString("android_latest_version") } catch (e: Exception) { null }
                    androidLatestVersion?.let {
                        this.androidLatestVersion = it
                    }
                }
            }

            val uiHandler = android.os.Handler(Looper.getMainLooper())
            uiHandler.post({
                completionHandler(error)
            })
        })
    }

    // 40
    // SMS認証コード発行
    fun userSMSSend(phone: String, completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/user/sms/send"
        val params = JSONObject()

        params.put("phone", phone)

        this.post(url = url, params = params, completionHandler = completionHandler)
    }

    // SMS認証
    fun userSMSAuth(phone: String, pincode: String, completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/user/sms/auth"
        val params = JSONObject()

        params.put("phone", phone)
        params.put("pincode", pincode)

        this.post(url = url, params = params, completionHandler = completionHandler)
    }

    // ユーザー存在確認
    fun userFind(mailAddress: String, provider: String = "facebook", accessToken: String? = null, accessTokenSecret: String = "", completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/user/find"
        val params = JSONObject()

        params.put("mail_address", mailAddress)
        params.put("provider", provider)
        if (accessToken != null) {
            params.put("access_token", accessToken)
        }
        params.put("access_token_secret", accessTokenSecret)

        this.post(url = url, params = params, completionHandler = completionHandler)
    }

    // ユーザー登録
    fun userSignup(mailAddress: String, phone: String, password: String, completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/user/signup"
        val params = JSONObject()

        params.put("mail_address", mailAddress)
        params.put("phone", phone)
        params.put("password", password)

        this.post(url = url, params = params, completionHandler = completionHandler)
    }

    // OAuthユーザー登録/ログイン
    fun userOAuth(accessToken: String?, accessTokenSecret: String = "", provider: String = "facebook", phone: String? = null, completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/user/oauth"
        val params = JSONObject()

        params.put("provider", provider)
        if (accessToken != null) {
            if (phone == null) {
                params.put("access_token", accessToken)
            } else {
                params.put("access_token", accessToken)
                params.put("phone", phone)
            }
        }
        params.put("access_token_secret", accessTokenSecret)

        this.post(url = url, params = params, completionHandler = completionHandler)
    }

    // 50
    // ユーザーログイン
    fun userSignin(mailAddress: String, password: String, completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/user/signin"
        val params = JSONObject()
        val authParams = JSONObject()

        authParams.put("mail_address", mailAddress)
        authParams.put("password", password)
        params.put("auth", authParams)

        this.post(url = url, params = params, completionHandler = completionHandler)
    }

    // ユーザーログアウト
    fun userSignout(jwt: String, completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/user/signout"
        val params = JSONObject()

        params.put("jwt", jwt)

        this.post(url = url, params = params, isJwt = true, completionHandler = completionHandler)
    }

    // ユーザ情報
    fun userInfo(completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/user/info"

        this.get(url = url, isJwt = true, completionHandler = completionHandler)
    }

    // パスワードリマインダー
    fun userPasswordReminder(reminder: String, completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/user/password_reminder"
        val params = JSONObject()

        params.put("reminder", reminder)

        this.post(url = url, params = params, completionHandler = completionHandler)
    }

    // 30
    // ピックアップクリエイティブ
    fun talentListFeatured(completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/talent/list/featured"

        this.get(url = url, completionHandler = completionHandler)
    }

    // カテゴリ別銘柄一覧
    fun talentListCategory(categoryId: Int, completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/talent/list/category/" + categoryId

        this.get(url = url, completionHandler = completionHandler)
    }
    fun talentListCategory(page: Int, limit: Int, categoryId: Int, completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/talent/list/category/" + categoryId + "?page=" + page + "&limit=" + limit

        this.get(url = url, completionHandler = completionHandler)
    }

    // 公募一覧
    fun talentListSubscription(completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/talent/list/subscription"

        this.get(url = url, completionHandler = completionHandler)
    }

    // 60
    // 基本情報
    fun talent(talentId: Int, completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/talent/" + talentId

        this.get(url = url, isJwt = true, completionHandler = completionHandler)
    }

    // 取引情報
    fun talentTrade(talentId: Int, completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/talent/" + talentId + "/trade"

        this.get(url = url, completionHandler = completionHandler)
    }

    // 注文価格情報
    fun talentOrder(talentId: Int, completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/talent/" + talentId + "/order"

        this.get(url = url, isJwt = true, completionHandler = completionHandler)
    }

    // 注文
    fun talentOrder(talentId: Int, price: Double, volume: Int, orderingType: Int, uuid: String, completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/talent/order"
        val params = JSONObject()

        params.put("talent_id", talentId)
        params.put("price", price)
        params.put("volume", volume)
        params.put("ordering_type", orderingType)
        params.put("uuid", uuid)

        this.post(url = url, params = params, isJwt = true, completionHandler = completionHandler)
    }

    // 70
    // 保有銘柄一覧
    fun historyPosition(completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/history/position"

        this.get(url = url, isJwt = true, completionHandler = completionHandler)
    }

    // 注文履歴一覧
    fun historyOrder(offset: Int, limit: Int, status: Int, completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/history/order?offset=${offset}&limit=${limit}&status=${status}"

        this.get(url = url, isJwt = true, completionHandler = completionHandler)
    }

    // 注文キャンセル
    fun orderCancel(orderingId: Int, completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/order/" + orderingId + "/cancel"
        val params = JSONObject()

        this.post(url = url, params = params, isJwt = true, completionHandler = completionHandler)
    }

    // 約定履歴一覧
    fun historyTransaction(offset: Int, limit: Int, completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/history/transaction?offset=${offset}&limit=${limit}"

        this.get(url = url, isJwt = true, completionHandler = completionHandler)
    }

    // 公募申し込み履歴一覧
    fun historySubscription(completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/history/subscription"

        this.get(url = url, isJwt = true, completionHandler = completionHandler)
    }

    // 公募申し込みキャンセル、追加公募申し込みキャンセル
    fun subscriptionCancel(subscriptionEntryId: Int, kind: Int, completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/subscription/" + subscriptionEntryId + "/cancel"
        val params = JSONObject()

        params.put("kind", kind) // 追加公募申し込みキャンセル

        this.post(url = url, params = params, isJwt = true, completionHandler = completionHandler)
    }

    // 付与履歴一覧
    fun historyImpart(completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/history/impart"

        this.get(url = url, isJwt = true, completionHandler = completionHandler)
    }

    // 利用履歴一覧
    fun historyExercise(completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/history/exercise"

        this.get(url = url, isJwt = true, completionHandler = completionHandler)
    }

    fun historyPayments(completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/history/payments"

        this.get(url = url, isJwt = true, completionHandler = completionHandler)
    }

    // 80
    // デポジット残高
    fun balance(completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/balance"

        this.get(url = url, isJwt = true, completionHandler = completionHandler)
    }

    // 入金方法一覧、入金履歴一覧
    fun balanceDeposit(completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/balance/deposit"

        this.get(url = url, isJwt = true, completionHandler = completionHandler)
    }

    // 残高情報、口座情報、出金履歴一覧
    fun balanceWithdrawal(completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/balance/withdrawal"

        this.get(url = url, isJwt = true, completionHandler = completionHandler)
    }

    // 出金申請
    fun balanceWithdrawal(withdrawalAmount: String, uuid: String, completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/balance/withdrawal"
        val params = JSONObject()

        params.put("withdrawal_amount", withdrawalAmount)
        params.put("uuid", uuid)

        this.post(url = url, params = params, isJwt = true, completionHandler = completionHandler)
    }

    // 90
    // 口座情報取得
    fun userBankAccount(completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/user/bank_account"

        this.get(url = url, isJwt = true, completionHandler = completionHandler)
    }

    // 口座情報登録/編集
    fun userBankAccount(financialInstitutionNumber: String, branchTransitNumber: String, accountType: Int, accountNumber: String, accountName: String, completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/user/bank_account"
        val params = JSONObject()

        params.put("financial_institution_number", financialInstitutionNumber)
        params.put("branch_transit_number", branchTransitNumber)
        params.put("account_type", accountType)
        params.put("account_number", accountNumber)
        params.put("account_name", accountName)

        this.post(url = url, params = params, isJwt = true, completionHandler = completionHandler)
    }

    // メールアドレス変更
    fun userMailAddress(mailAddress: String, completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/user/mail_address"
        val params = JSONObject()

        params.put("mail_address", mailAddress)

        this.post(url = url, params = params, isJwt = true, completionHandler = completionHandler)
    }

    // パスワード更新
    fun userPassword(currentPassword: String, password: String, passwordConfirmation: String, completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/user/password"
        val params = JSONObject()

        params.put("current_password", currentPassword)
        params.put("password", password)
        params.put("password_confirmation", passwordConfirmation)

        this.post(url = url, params = params, isJwt = true, completionHandler = completionHandler)
    }

    // 招待コード取得
    fun invitationCode(completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/invitation_token"

        this.get(url = url, isJwt = true, completionHandler = completionHandler)
    }

    // 招待コード登録
    fun invitationCode(invitationToken: String, completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/invitation_histories"
        val params = JSONObject()

        params.put("invitation_token", invitationToken)

        this.post(url = url, params = params, isJwt = true, completionHandler = completionHandler)
    }

    // 100
    // 公募情報取得
    fun subscription(subscriptionId: Int, talentId: Int, completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/subscription/" + subscriptionId + "?talent_id=" + talentId

        this.get(url = url, isJwt = true, completionHandler = completionHandler)
    }

    // 公募申し込み、追加公募申し込み
    fun subscription(subscriptionId: Int, second: Int, talentId: Int, completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/subscription/"
        val params = JSONObject()

        params.put("subscription_id", subscriptionId)
        params.put("second", second)
        params.put("talent_id", talentId) // 追加公募申し込み

        this.post(url = url, params = params, isJwt = true, completionHandler = completionHandler)
    }

    // 110
    // 利用情報詳細取得
    fun exercise(talentId: Int, completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/exercise/" + talentId

        this.get(url = url, isJwt = true, completionHandler = completionHandler)
    }

    // 利用
    fun exercise(talentId: Int, exerciseMenuId: Int, second: Int, completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/exercise"
        val params = JSONObject()

        params.put("talent_id", talentId)
        params.put("exercise_menu_id", exerciseMenuId)
        params.put("second", second)

        this.post(url = url, params = params, isJwt = true, completionHandler = completionHandler)
    }

    // お知らせ一覧取得
    fun notifications(completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/notifications?read=all"
        this.get(url = url, isJwt = true, completionHandler = completionHandler)
    }

    // お知らせを既読に変更
    fun notifications(notificationId: Int, completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/notifications/" + notificationId.toString()
        val params = JSONObject()

        params.put("notification_id", notificationId)


        put(url, params, true, completionHandler)
    }

    // お知らせバッジの取得
    fun badges(completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/badges"

        get(url, true, completionHandler)
    }

    // 投票所タレント一覧取得
    fun preTalents(offset: Int, limit: Int, categoryId: Int, preTalentId: Int? = null, completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/pre_talents?offset=" + offset + "&limit=" + limit + "&category=" + categoryId + "&pre_talent_id=" + if (preTalentId != null) preTalentId else ""

        get(url, true, completionHandler)
    }

    // 投票所タレント詳細取得
    fun preTalents(preTalentId: Int, completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/pre_talents/" + preTalentId

        get(url, true, completionHandler)
    }

    // 投票所タレント応援
    fun preTalentsCheer(preTalentId: Int, completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/pre_talents/" + preTalentId

        put(url, JSONObject(), true, completionHandler)
    }

    // 時短課金
    fun preTalentsAdditionalVotes(preTalentId: Int, completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/pre_talents/" + preTalentId + "/additional_votes"

        put(url, JSONObject(), true, completionHandler)
    }

    // ログインチェック
    fun loggedin(completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/loggedin"

        get(url, true, completionHandler)
    }

    // 事前公募情報取得
    fun preSales(preSaleId: Int, completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/pre_sales/" + preSaleId

        get(url, true, completionHandler)
    }

    // 事前公募購入
    fun pollingStations(preSalesId: Int, buySecond: Int, completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/polling_stations/" + preSalesId
        val params = JSONObject()
        params.put("buy_second", buySecond)

        post(url, params, true, completionHandler)
    }

    // 120
    // タイムライン用トーク一覧
    fun talentQasTimeline(sortType: String, filterType: String, pageNumber: Int, limitNumber: Int, completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/talent_qas/timeline?sort_by=" + sortType + "&filter=" + filterType + "&page=" + pageNumber + "&limit=" + limitNumber

        get(url, true, completionHandler)
    }

    // 銘柄詳細用トーク一覧
    fun talentQas(talentId: Int, condition: String, pageNumber: Int, limitNumber: Int, nextUrl: String? = null, completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        if (nextUrl != null) {
            get(nextUrl, true, completionHandler)
            return
        }
        val url = BASE_URL + "/talent_qas?talent_id=${talentId}&condition=${condition}&page=${pageNumber}&limit=${limitNumber}"

        get(url, true, completionHandler)
    }

    // ユーザ一覧
    fun talentQaRead(id: Int, completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/talent_qa/" + id + "/read"

        get(url, true, completionHandler)
    }

    // 閲覧実行
    fun talentQaReadExecute(talentId: Int, talentQaId: Int, completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/talent_qa/read"
        val params = JSONObject()

        params.put("talent_id", talentId)
        params.put("talent_qa_id", talentQaId)

        post(url, params, true, completionHandler)
    }

    // 130
    // 聞きたい
    fun talentQasWants(talentQaId: Int, completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/talent_qas/" + talentQaId + "/wants"
        val params = JSONObject()

        post(url, params, true, completionHandler)
    }

    // 140
    // いいね
    fun talentQasLikes(talentQaId: Int, evaluation: Int?, completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/talent_qas/" + talentQaId + "/likes"
        val params = JSONObject()
        evaluation?.let {
            params.put("evaluation", evaluation)
        }

        post(url, params, true, completionHandler)
    }

    // 質問投稿
    fun talentQAs(talentId: Int, questionText: String, completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/talent_qas"
        val params = JSONObject().apply {
            this.put("talent_id", talentId)
            this.put("question", questionText)
        }

        post(url, params, true, completionHandler)
    }

    fun answerQA(kind: Int, talentQaId: Int, answer: String, answerType: String, spend: Int, completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/talent_qas/${talentQaId}"
        val params = JSONObject().apply {
            this.put("kind", kind)
            this.put("answer", answer)
            this.put("answer_type", answerType)
            this.put("spend", spend)
        }

        patch(url, params, true, completionHandler)
    }

    //エールランキング
    fun talentYellRanking(talentId: Int, nextUrl: String? = null, completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        if (nextUrl != null) {
            get(nextUrl, true, completionHandler)
            return
        }
        val url = BASE_URL + "/talent/${talentId}/yell_ranking"

        get(url, true, completionHandler)
    }

    fun talentYellMyRanking(talentId: Int, completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/talent/${talentId}/my_yell_ranking"

        get(url, true, completionHandler)
    }

    fun userProfile(userId: Int, completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/user/${userId}/profile"

        get(url, true, completionHandler)
    }

    // 短いダイナミックリンクを作成
    fun shortLinks(link: String, socialParams: JSONObject?, completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        // 共通プログレスサークルを表示
        showBaseProgressCircle()

        val longLink = FirebaseDynamicLinks.getInstance().createDynamicLink()
                .setLink(Uri.parse(link))
                .setDynamicLinkDomain(jp.timebank.BuildConfig.DYNAMIC_LINK_DOMAIN)
                .setAndroidParameters(
                        DynamicLink.AndroidParameters.Builder(jp.timebank.BuildConfig.APPLICATION_ID)
                                .setMinimumVersion(jp.timebank.BuildConfig.FIREBASE_ANDROID_MIN_VERSION)
                                .build())
                .setIosParameters(
                        DynamicLink.IosParameters.Builder(jp.timebank.BuildConfig.IOS_BUNDLE_ID)
                                .setAppStoreId(jp.timebank.BuildConfig.FIREBASE_APP_STORE_ID)
                                .setMinimumVersion(jp.timebank.BuildConfig.FIREBASE_IOS_MIN_VERSION)
                                .build())
                .setSocialMetaTagParameters(
                        DynamicLink.SocialMetaTagParameters.Builder().apply {
                            socialParams?.let {
                                if (socialParams.has("title")) {
                                    setTitle(socialParams.getString("title"))
                                }
                                if (socialParams.has("description")) {
                                    setDescription(socialParams.getString("description"))
                                }
                                if (socialParams.has("imageUrl")) {
                                    setImageUrl(Uri.parse(socialParams.getString("imageUrl")))
                                }
                            }
                        }.build())
                .buildDynamicLink()
                .uri.toString()
        FirebaseDynamicLinks.getInstance().createDynamicLink()
                .setLongLink(Uri.parse(longLink + "&dfl=https%3A%2F%2Fitunes.apple.com%2Fjp%2Fapp%2Fid1253351424%2F"))
                .buildShortDynamicLink()
                .addOnCompleteListener { task ->
                    // 共通プログレスサークルを非表示
                    hideBaseProgressCircle()

                    if (task.isSuccessful) {
                        // Short link created
                        val result = JSONObject().apply {
                            put("shortLink", task.result.shortLink)
                            put("previewLink", task.result.previewLink)
                        }
                        completionHandler(result, null)
                    } else {
                        // Error
                        completionHandler(null, task.exception as ApiError.Json)
                    }
                }
    }

    // 発信情報作成
    // - Note: 発信に紐付けてメディアを送る場合は上記Responseのtalent_qa_idをメディア情報と共に送る
    fun talentQAsTransmission(kind: Int, description: String, sentence: String = "", disclosureType: String, requiredQuantity: Int, completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/talent_qas/transmission"
        val params = JSONObject().apply {
            this.put("kind", kind) // テキスト: 0, ボイス: 5, 画像: 10, 動画: 15
            this.put("sentence", sentence) // 本文（オプション kindがtextのときのみ）
            this.put("description", description) // 説明文
            this.put("disclosure_type", disclosureType) // 投稿の公開範囲 全員に公開: every, タイムオーナーのみ: owner, 購入者のみ: pay
            this.put("required_quantity", requiredQuantity) // 消費秒数
        }

        post(url, params, true, completionHandler)
    }

    // 発信情報編集
    // - Note: 発信に紐付けてメディアを送る場合は上記Responseのtalent_qa_idをメディア情報と共に送る
    fun talentQAsTransmission(talentQaId: Int, description: String, sentence: String = "", completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/talent_qas/transmission/$talentQaId"
        val params = JSONObject().apply {
            this.put("sentence", sentence) // 本文（オプション kindがtextのときのみ）
            this.put("description", description) // 説明文
        }

        patch(url, params, true, completionHandler)
    }

    // ボイスupload
    fun talentQAsUploadsVoice(talentQaId: Int, media: String, mediaSecond: Int, mediaContainerType: String, completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = MEDIA_UPLOAD_URL + "/ta/talent_qas/uploads/$talentQaId/voice"
        val params = JSONObject().apply {
            this.put("media", media) // ボイス
            this.put("media_second", mediaSecond) // メディアの長さ
            this.put("media_container_type", mediaContainerType) // コンテナタイプ（デフォルトではwavでくると想定しています）
        }

        post(url, params, true, completionHandler)
    }

    // その他メニュー取得
    fun otherPages(completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/other_pages"
        get(url, true, completionHandler)
    }

    // ユーザーの口座残高を取得
    fun fetchCurretCapacity(completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/user/current_capacity"

        get(url, true, completionHandler)
    }

    // エール登録
    fun yell(talentQaId: Int, yellCount: Int, completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/talent_qa/yell/$talentQaId"
        val params = JSONObject().apply {
            this.put("yell_count", yellCount) // エール数
            this.put("uuid", UUID.randomUUID()) // UUID
        }

        post(url, params, true, completionHandler)
    }

    // プレゼントボックス取得
    fun getUserBox(completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/user/box"

        get(url, true, completionHandler)
    }

    // チケット利用履歴取得
    fun getUserBoxHistories(offset: Int, limit: Int, completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/user/box/histories?offset=${offset}&limit=${limit}"

        get(url, true, completionHandler)
    }

    // 抽選API
    fun postItems(kind: Int, completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/items"
        val params = JSONObject(mapOf("kind" to kind)) // ??

        post(url, params, true, completionHandler)
    }

    // 抽選結果取得
    fun getItems(id: Int, completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        val url = BASE_URL + "/items/$id"

        get(url, true, completionHandler)
    }

    fun xUserAgent(): String {
        val androidVer = Build.VERSION.RELEASE
        val device = Build.MODEL
        val appVer = jp.timebank.BuildConfig.VERSION_NAME

        if (jp.timebank.BuildConfig.DEBUG) {
            Log.d(LogTag, "UserAgent: Timebank/$appVer ($device; Android $androidVer)")
        }

        return "Timebank/$appVer ($device; Android $androidVer)"
    }

    // get, post, request
    // getメソッド
    fun get(url: String?, isJwt: Boolean = false, completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        if (url == null) {
            val uiHandler = android.os.Handler(Looper.getMainLooper())
            uiHandler.post({
                completionHandler(null, ApiError.Url())
            })
            return
        }

        this.request(method = Request.Method.GET, url = url, isJwt = isJwt, completionHandler = completionHandler)
    }
    fun get(url: String?, params: JSONObject, isJwt: Boolean = false, completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        if (url == null) {
            val uiHandler = android.os.Handler(Looper.getMainLooper())
            uiHandler.post({
                completionHandler(null, ApiError.Url())
            })
            return
        }

        this.request(method = Request.Method.GET, url = url, params = params, isJwt = isJwt, completionHandler = completionHandler)
    }

    // postメソッド
    fun post(url: String?, params: JSONObject, isJwt: Boolean = false, completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        if (url == null) {
            val uiHandler = android.os.Handler(Looper.getMainLooper())
            uiHandler.post({
                completionHandler(null, ApiError.Url())
            })
            return
        }

        this.request(method = Request.Method.POST, url = url, params = params, isJwt = isJwt, completionHandler = completionHandler)
    }

    // putメソッド
    fun put(url: String?, params: JSONObject, isJwt: Boolean = false, completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        if (url == null) {
            val uiHandler = android.os.Handler(Looper.getMainLooper())
            uiHandler.post({
                completionHandler(null, ApiError.Url())
            })
            return
        }

        this.request(method = Request.Method.PUT, url = url, params = params, isJwt = isJwt, completionHandler = completionHandler)
    }

    // patchメソッド
    fun patch(url: String?, params: JSONObject, isJwt: Boolean = false, completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {
        if (url == null) {
            val uiHandler = android.os.Handler(Looper.getMainLooper())
            uiHandler.post({
                completionHandler(null, ApiError.Url())
            })
            return
        }

        val method: Int
        val isOverrideAsPatchMethod: Boolean
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) { // Android 5.0以降
            method = Request.Method.PATCH
            isOverrideAsPatchMethod = false
        } else { // Android4.4
            method = Request.Method.POST
            isOverrideAsPatchMethod = true // 「X-HTTP-Method-Override: PATCH」というヘッダーを付加したPOSTリクエストで代用する
        }

        this.request(method = method, url = url, params = params, isJwt = isJwt, isOverrideAsPatchMethod = isOverrideAsPatchMethod, completionHandler = completionHandler)
    }

    // requestメソッド
    fun request(method: Int, url: String, params: JSONObject? = null, isJwt: Boolean, isOverrideAsPatchMethod: Boolean = false, completionHandler: (result: JSONObject?, error: Exception?) -> Unit) {

        if (jp.timebank.BuildConfig.DEBUG) {
            Log.d(LogTag, "WebApi URL: " + url)
            params?.let {
                for (key in params.keys()) {
                    Log.d(LogTag, "WebApi PARAM $key: " + params.get(key))
                }
            }
        }

        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkInfo = connectivityManager.getActiveNetworkInfo()
        val isConnected = (networkInfo != null && networkInfo.isConnected())
        if (!isConnected) {
            val uiHandler = android.os.Handler(Looper.getMainLooper())
            uiHandler.post({
                completionHandler(null, ApiError.Offline())
            })
            return
        }

        val request = object: JsonObjectRequest(method, url, params,
                Response.Listener<JSONObject> { response ->
                    var error: Exception? = null
                    var result: JSONObject? = response

                    when (statusCode) {
                        200 -> { // OK
                            if (result == null) {
                                Log.e(LogTag, "Error:API Response Data Nil")
                                error = ApiError.DataNil()
                            } else {

                                if (BuildConfig.DEBUG) {
                                    Log.i(LogTag, "data:" + result.toString())
                                }

                                val meta = try { result.getJSONObject("meta") } catch (e: Exception) { null }
                                if (meta != null) {
                                    // return from Timebank API
                                    val status = try { meta.getInt("status") } catch (e: Exception) { null }
                                    val message = try { meta.getJSONArray("message") } catch (e: Exception) { null }
                                    if (status == null || message == null) {
                                        Log.e(LogTag, "Error:API Response Json Error")
                                        error = ApiError.Json(url, statusCode, response?.toString())
                                    } else {
                                        when (status) {
                                            20 -> { // 成功
                                                val body = try { result.getJSONObject("body") } catch (e: Exception) { null }
                                                result = body
                                            }
                                            40 -> { // ログイントークンエラー
                                                error = ApiError.LoginToken(message)
                                            }
                                            41 -> { // バリデーションエラー
                                                error = ApiError.Validation(message)
                                            }
                                            42 -> { // OAuthアクセストークンエラー
                                                error = ApiError.OAuthAccessToken(message)
                                            }
                                            43 -> { // ログイン認証(JWT)エラー
                                                error = ApiError.Login(message)

                                                // jwt破棄
                                                val defaultSharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
                                                val editor = defaultSharedPreferences.edit()
                                                editor.remove(JwtKey)
                                                editor.commit()
                                            }
                                            44 -> { // 注文エラー
                                                error = ApiError.Order(message)
                                            }
                                            45 -> { // 利用エラー
                                                error = ApiError.Exercise(message)
                                            }
                                            46 -> { // サインインエラー
                                                error = ApiError.SignIn(message)
                                            }
                                            47 -> { // すでに登録済みのユーザー
                                                error = ApiError.ExistUser(message)
                                            }
                                            48 -> { // OAuth登録済みのユーザー
                                                val body = try { result.getJSONObject("body") } catch (e: Exception) { null }
                                                result = body
                                                error = ApiError.ExistOAuth(message)
                                            }
                                            49 -> { // メールアドレスは存在していて、OAuthに登録されていないユーザー(providerを間違えているなど)
                                                error = ApiError.ExistUserNoOAuth(message)
                                            }
                                            51 -> { // メンテナンス(ダイアログにmessage表示)
                                                error = ApiError.Maintenance(1, message, "")
                                            }
                                            52 -> { // メンテナンス(ダイアログにmessage表示、詳細ボタンでWebView)
                                                val maintenanceWebviewUrl = try { meta.getString("maintenance_webview_url") } catch (e: Exception) { "" }
                                                error = ApiError.Maintenance(2, message, maintenanceWebviewUrl)
                                            }
                                            53 -> { // メンテナンス(直接、WebView表示)
                                                val maintenanceWebviewUrl = try { meta.getString("maintenance_webview_url") } catch (e: Exception) { "" }
                                                error = ApiError.Maintenance(3, message, maintenanceWebviewUrl)
                                            }
                                            99 -> { // 想定外のエラー
                                                error = ApiError.Unexpected(message)
                                            }
                                            else -> {
                                                error = ApiError.Other(message)
                                            }
                                        }
                                    }
                                } else {
                                    val shortLink = try { result.getString("shortLink") } catch (e: Exception) { null }
                                    val previewLink = try { result.getString("previewLink") } catch (e: Exception) { null }
                                    if (shortLink != null && previewLink != null) {
                                        // return from Firebase short links API
                                        // no code
                                    } else {
                                        // Error
                                        Log.e(LogTag, "Error:API Response Json Error")
                                        error = ApiError.Json(url, statusCode, response?.toString())
                                    }
                                }
                            }
                        }
                        201 -> { // OK(singin API成功の場合)
                            if (result != null) {
                                if (BuildConfig.DEBUG) {
                                    Log.i(LogTag, "data:" + result.toString())
                                }
                            } else {
                                Log.e(LogTag, "Error:API Response Data Nil")
                                error = ApiError.DataNil()
                            }
                        }
                        else -> {
                            Log.e(LogTag, "Error:API Response Http StatusCode=" + statusCode)
                            error = ApiError.Http(statusCode)
                        }
                    }

                    // 共通プログレスサークルを非表示
                    hideBaseProgressCircle()

                    completionHandler(result, error)
                },
                Response.ErrorListener { error ->
                    // 共通プログレスサークルを非表示
                    hideBaseProgressCircle()

                    completionHandler(null, error)
                }) {
            @Throws(AuthFailureError::class)
            override fun getHeaders(): Map<String, String> {
                val headers = HashMap<String, String>()

                headers.put("Content-Type", "application/json")
                headers.put("Accept", "application/json, version=${jp.timebank.BuildConfig.API_VERSION}")

                if (isOverrideAsPatchMethod) {
                    headers.put("X-HTTP-Method-Override", "PATCH")
                }

                if (isJwt) {
                    val defaultSharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
                    val jwt = defaultSharedPreferences.getString(JwtKey, null)
                    jwt?.let {
                        headers.put("Authorization", "Bearer " + it)
                    }
                }
                val userAgent = xUserAgent()
                headers.put("X-User-Agent", userAgent)
                headers.put("X-TIMEBANK-ADID", TimebankApplication.advertisingId ?: "")
                if (jp.timebank.BuildConfig.DEBUG) {
                    Log.d(LogTag, "WebApi X-User-Agent: " + userAgent)
                    Log.d(LogTag, "WebApi X-TIMEBANK-ADID: " + TimebankApplication.advertisingId ?: "")
                }

                return headers
            }

            override protected fun parseNetworkResponse(response: NetworkResponse): Response<JSONObject> {
                statusCode = response.statusCode

                return super.parseNetworkResponse(response)
            }
        }

        // 共通プログレスサークルを表示
        showBaseProgressCircle()

        request.setShouldCache(false)
        // タイムアウトを60秒に設定
        val policy = DefaultRetryPolicy(networkTimeout, networkRetryCount, networkBackoffMult)
        request.retryPolicy = policy
        if (jp.timebank.BuildConfig.DEBUG) {
            request.headers.forEach { (key, value) ->
                Log.d(LogTag, "RequestHeader $key:$value")
            }
        }
        requestQueue.add<JSONObject>(request)
    }


    // Download Image

    private class DownloadImageTask(val url: String, val width: Int? = null, val height: Int? = null, val completionHandler: (image: Bitmap?, error: Exception?) -> Unit): AsyncTask<Unit, Unit, Unit>() {
        var urlConnection = URL(url).openConnection() as HttpURLConnection
        var inputStream: InputStream? = null
        var downloadedImage: Bitmap? = null
        var error: Exception? = null

        companion object {
            private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int?): Int {

                // 画像の元サイズ
                val width = options.outWidth
                var inSampleSize = 1

                if (reqWidth != null && width > reqWidth) {
                    inSampleSize = Math.round(width.toFloat() / reqWidth.toFloat())
                }
                return inSampleSize
            }
        }

        override fun doInBackground(vararg p0: Unit?) {
            try {
                // 不要なビットマップリソースをリサイクル
                downloadedImage?.recycle()

                urlConnection.requestMethod = "GET"
                urlConnection.connect()

                when (urlConnection.responseCode) {
                    HttpURLConnection.HTTP_OK -> {

                        val options = bitmapOptions()

                        options.inSampleSize = calculateInSampleSize(options, width)
                        options.inJustDecodeBounds = false
                        if (jp.timebank.BuildConfig.DEBUG) {
                            Log.d("image url", url)
                            Log.d("image inSampleSize", options.inSampleSize.toString())
                        }

                        inputStream = urlConnection.inputStream
                        downloadedImage = BitmapFactory.decodeStream(inputStream, null, options)

                        // 取得したイメージをcacheに保持
                        if (downloadedImage != null) {
                            if (width != null && height != null) {
                                // 取得したイメージが長方形、また表示したいviewが正方形の場合、取得したイメージの長方形の中心部の正方形をカット
                                if (downloadedImage!!.width != downloadedImage!!.height && width == height) {
                                    downloadedImage = cutBitmapCenterSquare(downloadedImage!!)
                                }
                                // 取得したイメージをリサイズ
                                downloadedImage = Bitmap.createScaledBitmap(downloadedImage, width, height, true)
                            }
                            WebApi.imageCache.put(url + width + height, downloadedImage)
                        } else {
                            error = ApiError.DataNil()
                        }
                    }
                    else -> {
                        error = ApiError.Http(status = urlConnection.responseCode)
                    }
                }
            } catch (e: MalformedURLException) {
                error = ApiError.Url()
            } catch (e: IOException) {
                error = ApiError.Url()
            } catch (e: SocketTimeoutException) {
                error = ApiError.Offline()
            } catch (e: FileNotFoundException) {
                error = ApiError.DataNil()
            } catch (e: Exception) {
                error = e
            } finally {
                inputStream?.close()
                urlConnection.disconnect()
            }
            return
        }

        override fun onPostExecute(result: Unit){
            completionHandler(downloadedImage, error)
        }

        fun bitmapOptions(): BitmapFactory.Options {
            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true

            val urlConnection = URL(url).openConnection() as HttpURLConnection
            urlConnection.requestMethod = "GET"
            urlConnection.connect()

            when (urlConnection.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val inputStream = urlConnection.inputStream
                    BitmapFactory.decodeStream(inputStream, null, options)
                    inputStream?.close()
                }
            }
            urlConnection.disconnect()

            return options
        }

        // 長方形中心部の正方形をカット
        fun cutBitmapCenterSquare(bitmap: Bitmap): Bitmap {
            return if (bitmap.width >= bitmap.height) {
                Bitmap.createBitmap(
                        bitmap,
                        bitmap.width / 2 - bitmap.height / 2,
                        0,
                        bitmap.height,
                        bitmap.height
                )
            } else {
                Bitmap.createBitmap(
                        bitmap,
                        0,
                        bitmap.height / 2 - bitmap.width / 2,
                        bitmap.width,
                        bitmap.width
                )
            }
        }
    }

    fun downloadImage(url: String, width: Int? = null, height: Int? = null, completionHandler: (image: Bitmap?, error: Exception?) -> Unit): AsyncTask<Unit, Unit, Unit>? {

        // イメージがcacheにあるかチェック
        val image = try { WebApi.imageCache.get(url + width + height) as Bitmap } catch (e: Exception) { null }
        image?.let {
            completionHandler(image, null)
            return null
        }


        val task = DownloadImageTask(url, width, height, completionHandler)
        task.execute()

        return task
    }

    // Download Voice

    fun downloadVoice(url: String, completionHandler: (path: String?, error: Exception?) -> Unit) {

        val voiceFilename = url.split("?").first().split("/").last()

        // ボイスがcacheにあるかチェック
        val voiceCachePath = "${context.cacheDir.absolutePath}/$voiceFilename"
        val voiceCacheFile = File(voiceCachePath)
        if (voiceCacheFile.isFile && voiceCacheFile.exists()) {
            completionHandler(voiceCachePath, null)
            return
        }

        val request = object : Request<ByteArray>(Request.Method.GET, url,
                Response.ErrorListener { error ->
                    // 共通プログレスサークルを非表示
                    hideBaseProgressCircle()

                    error?.let {
                        error.networkResponse?.let { networkResponse -> // レスポンスが取得できた
                            Log.e(LogTag, "Error:Voice Response Data Error")

                            val response = String(networkResponse.data) // レスポンスBody（XML）を取得
                            Rollbar.reportException(error, "error", response) // Rollbarに送信

                            completionHandler(null, error)
                        } ?: run { // レスポンスが取得できない
                            Log.e(LogTag, "Error:Voice Response Data Error")

                            val response = "${error.javaClass.name}\n${error.message}" // クラス名とメッセージを取得
                            Rollbar.reportException(error, "error", response) // Rollbarに送信

                            completionHandler(null, error)
                        }
                    } ?: run {
                        Log.e(LogTag, "Error:Voice Response Data Nil")
                        completionHandler(null, ApiError.DataNil())
                    }
                }) {

            override fun deliverResponse(response: ByteArray?) {
                var inputStream: InputStream? = null
                var outputStream: OutputStream? = null
                var voicePath: String? = null
                var error: Exception? = null
                val result: ByteArray? = response

                when (statusCode) {
                    200 -> { // OK
                        if (result == null) {
                            Log.e(LogTag, "Error:Voice Response Data Nil")
                            error = ApiError.DataNil()
                        } else {

                            if (BuildConfig.DEBUG) {
                                Log.i(LogTag, "data size:" + result.size)
                            }

                            try {
                                inputStream = ByteArrayInputStream(result)
                                outputStream = FileOutputStream(File(voiceCachePath))
                                inputStream.use { it.copyTo(outputStream) }
                                outputStream.close()
                                inputStream.close()

                                // 取得したイメージをcacheに保持
                                if (File(voiceCachePath).exists() && File(voiceCachePath).length() > 0) {
                                    voicePath = voiceCachePath
                                } else {
                                    error = ApiError.DataNil()
                                }
                            } catch (e: IOException) {
                                error = ApiError.Url()
                            } catch (e: FileNotFoundException) {
                                error = ApiError.DataNil()
                            } catch (e: Exception) {
                                error = e
                            } finally {
                                inputStream?.close()
                                outputStream?.close()
                            }
                        }
                    }
                    else -> {
                        Log.e(LogTag, "Error:API Response Http StatusCode=" + statusCode)
                        error = ApiError.Http(statusCode)
                    }
                }

                // 共通プログレスサークルを非表示
                hideBaseProgressCircle()

                completionHandler(voicePath, error)
            }

            override fun parseNetworkResponse(response: NetworkResponse?): Response<ByteArray> {
                statusCode = response?.statusCode ?: 200

                val parsed: ByteArray = try {
                    response?.data ?: ByteArray(0)
                } catch (e: UnsupportedEncodingException) {
                    ByteArray(0)
                }

                return Response.success<ByteArray>(parsed, HttpHeaderParser.parseCacheHeaders(response))
            }
        }

        request.setShouldCache(false)
        // タイムアウトを60秒に設定
        val policy = DefaultRetryPolicy(networkTimeout, networkRetryCount, networkBackoffMult)
        request.retryPolicy = policy
        if (jp.timebank.BuildConfig.DEBUG) {
            request.headers.forEach { (key, value) ->
                Log.d(LogTag, "RequestHeader $key:$value")
            }
        }
        requestQueue.add(request)
    }

    fun showBaseProgressCircle() {
        // 共通プログレスサークルを表示
        val baseProgressBar: ProgressBar? = targetActivity?.findViewById(jp.timebank.R.id.base_progress_bar)
        baseProgressBar?.visibility = View.VISIBLE
    }

    fun hideBaseProgressCircle() {
        // 共通プログレスサークルを非表示
        val baseProgressBar: ProgressBar? = targetActivity?.findViewById(jp.timebank.R.id.base_progress_bar)
        baseProgressBar?.visibility = View.GONE
    }

    // Check Need App Update

    fun isNeedAppUpdate(): Boolean {
        var isNeedUpdate = false

        WebApi.androidLatestVersion?.let { androidLatestVersion ->
            val appVer = jp.timebank.BuildConfig.VERSION_NAME
            val appVer1 = appVer.split(".")[0].toInt()
            val appVer2 = appVer.split(".")[1].toInt()
            val ver1 = androidLatestVersion.split(".")[0].toInt()
            val ver2 = androidLatestVersion.split(".")[1].toInt()

            if (ver1 == appVer1) {
                if (ver2 > appVer2) {
                    // アプリのアップデートが必要
                    isNeedUpdate = true
                }
            } else if (ver1 > appVer1) {
                // アプリのアップデートが必要
                isNeedUpdate = true
            }
        }

        return isNeedUpdate
    }

    // Multipart upload

    fun mediaUpload(multipartList: ArrayList<GalleryPickerPreviewActivity.MultiPart>, completionHandler: (result: JsonObject?, error: Exception?) -> Unit) {

        val url = ""
        var multipartEntityBuilder = MultipartEntityBuilder.create()
        var entity: HttpEntity? = null

        val request = object : Request<String>(Method.POST, url,
                    Response.ErrorListener { error ->
                        // 共通プログレスサークルを非表示
                        hideBaseProgressCircle()

                        error?.let {
                            error.networkResponse?.let { networkResponse -> // レスポンスが取得できた
                                Log.e(LogTag, "Error:Voice Response Data Error")

                                val response = String(networkResponse.data) // レスポンスBody（XML）を取得
                                Rollbar.reportException(error, "error", response) // Rollbarに送信

                                completionHandler(null, error)
                            } ?: run { // レスポンスが取得できない
                                Log.e(LogTag, "Error:Voice Response Data Error")

                                val response = "${error.javaClass.name}\n${error.message}" // クラス名とメッセージを取得
                                Rollbar.reportException(error, "error", response) // Rollbarに送信

                                completionHandler(null, error)
                            }
                        } ?: run {
                            Log.e(LogTag, "Error:Voice Response Data Nil")
                            completionHandler(null, ApiError.DataNil())
                        }
                    }) {


            override fun deliverResponse(response: String?) {

            }

            override fun parseNetworkResponse(response: NetworkResponse?): Response<String>? {
                return null
            }

            override fun getBodyContentType(): String {
                return entity!!.contentType.value
            }

        }

        fun buildMultipartEntity(multipart: GalleryPickerPreviewActivity.MultiPart ) {

            multipartEntityBuilder.setMode(HttpMultipartMode.BROWSER_COMPATIBLE)

            multipartEntityBuilder.addBinaryBody("content", multipart.content)
            multipartEntityBuilder.addTextBody("priority", multipart.priority.toString())
            multipartEntityBuilder.addTextBody("type", multipart.type.toString())
            multipartEntityBuilder.addTextBody("filename", multipart.fileName)

//                multipartEntityBuilder.setBoundary("")
            entity = multipartEntityBuilder.build()
        }

        buildMultipartEntity(multipartList[0])
        requestQueue.add(request)
    }
}
