/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.scryer.detailpage

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.Bundle
import android.provider.Settings
import android.text.util.Linkify
import android.view.*
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.viewpager.widget.ViewPager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.ml.common.FirebaseMLException
import com.google.firebase.ml.vision.FirebaseVision
import com.google.firebase.ml.vision.common.FirebaseVisionImage
import com.google.firebase.ml.vision.text.FirebaseVisionText
import kotlinx.android.synthetic.main.activity_detail_page.*
import kotlinx.coroutines.experimental.*
import me.saket.bettermovementmethod.BetterLinkMovementMethod
import mozilla.components.browser.search.SearchEngineManager
import mozilla.components.browser.search.provider.AssetsSearchEngineProvider
import mozilla.components.browser.search.provider.localization.LocaleSearchLocalizationProvider
import org.mozilla.scryer.R
import org.mozilla.scryer.collectionview.OnDeleteScreenshotListener
import org.mozilla.scryer.collectionview.showDeleteScreenshotDialog
import org.mozilla.scryer.collectionview.showScreenshotInfoDialog
import org.mozilla.scryer.collectionview.showShareScreenshotDialog
import org.mozilla.scryer.persistence.CollectionModel
import org.mozilla.scryer.persistence.ScreenshotModel
import org.mozilla.scryer.preference.PreferenceWrapper
import org.mozilla.scryer.promote.Promoter
import org.mozilla.scryer.sortingpanel.SortingPanelActivity
import org.mozilla.scryer.telemetry.TelemetryWrapper
import org.mozilla.scryer.ui.ScryerToast
import org.mozilla.scryer.viewmodel.ScreenshotViewModel
import kotlin.coroutines.experimental.CoroutineContext
import kotlin.coroutines.experimental.suspendCoroutine

class DetailPageActivity : AppCompatActivity(), CoroutineScope {

    companion object Launcher {
        private const val EXTRA_SCREENSHOT_ID = "screenshot_id"
        private const val EXTRA_COLLECTION_ID = "collection_id"

        private const val SUPPORT_SLIDE = true

        private const val IMAGE_SCALE_NORMAL_MODE = 1f
        private const val IMAGE_SCALE_TEXT_MODE = 0.9f

        fun showDetailPage(context: Context, screenshot: ScreenshotModel, srcView: View?,
                           collectionId: String? = null) {
            val intent = Intent(context, DetailPageActivity::class.java)
//            val bundle = srcView?.let {
//                ViewCompat.getTransitionName(it)?.let { transitionName ->
//                    val option = ActivityOptionsCompat.makeSceneTransitionAnimation(
//                            context as Activity, srcView, transitionName)
//                    option.toBundle()
//                }
//            }
            intent.putExtra(EXTRA_SCREENSHOT_ID, screenshot.id)
            collectionId?.let {
                intent.putExtra(EXTRA_COLLECTION_ID, collectionId)
            }
            (context as AppCompatActivity).startActivity(intent)
        }
    }

    private var shareMenu: MenuItem? = null
    private var moveToMenu: MenuItem? = null
    private var screenshotInfoMenu: MenuItem? = null
    private var deleteMenu: MenuItem? = null
    private var selectAllMenu: MenuItem? = null

    /** Where did the user came from to this page **/
    private val srcCollectionId: String? by lazy {
        intent?.getStringExtra(EXTRA_COLLECTION_ID)
    }

    private val screenshotId: String by lazy {
        val id = intent?.getStringExtra(EXTRA_SCREENSHOT_ID)
        id ?: throw IllegalArgumentException("invalid screenshot id")
    }

    private val viewModel: ScreenshotViewModel by lazy {
        ScreenshotViewModel.get(this)
    }

    private lateinit var screenshots: List<ScreenshotModel>
    private val loadingViewController: LoadingViewGroup by lazy {
        LoadingViewGroup(this)
    }

    private val prefs = PreferenceWrapper(this)

    private var isRecognizing = false
    private var isTextMode = false
    private var isEnterTransitionPostponed = true

    private val adapter = DetailPageAdapter()
    private val graphicOverlayHelper: GraphicOverlayHelper by lazy {
        GraphicOverlayHelper(graphicOverlay)
    }

    /* whether the user has run ocr on the current image before swiping to the next one */
    private var hasRunOcr = false

    private val screenSize: RectF by lazy {
        val size = Point().apply {
            windowManager.defaultDisplay.getRealSize(this)
        }
        RectF(0f, 0f, size.x.toFloat(), size.y.toFloat())
    }

    private val itemCallback = object : DetailPageAdapter.ItemCallback {
        override fun onItemClicked(item: ScreenshotModel) {
            toggleActionBar()
        }

        override fun onItemLoaded(item: ScreenshotModel) {
            if (isEnterTransitionPostponed && item.id == screenshotId) {
                isEnterTransitionPostponed = false
                supportStartPostponedEnterTransition()
            }
        }
    }

    private val imageStateCallback = object : DetailPageAdapter.ImageStateCallback {
        override fun onScaleChanged(pageView: DetailPageAdapter.PageView) {
            view_pager.pageLocked = pageView.isScaled()
        }
    }

    private val activityJob = Job()

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + activityJob

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_detail_page)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE

        supportPostponeEnterTransition()

        initActionBar()
        initViewPager()
        initFab()
        initPanel()

        updateUI()

        if (!prefs.isOcrOnboardingShown()) {
            showOcrOnboarding()
        }

        TelemetryWrapper.viewScreenshot()
    }

    override fun onDestroy() {
        activityJob.cancel()
        super.onDestroy()
    }

    override fun onBackPressed() {
        when {
            isTextMode -> {
                isTextMode = false
                updateUI()
            }

            supportActionBar?.isShowing != true -> {
                toggleActionBar()
            }

            else -> {
                super.onBackPressed()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_detail, menu)

        if (menu != null) {
            shareMenu = menu.findItem(R.id.action_share)
            shareMenu?.let {
                val wrapped = DrawableCompat.wrap(it.icon).mutate()
                DrawableCompat.setTint(wrapped, ContextCompat.getColor(this, R.color.white))
            }

            moveToMenu = menu.findItem(R.id.action_move_to)
            if (srcCollectionId == CollectionModel.CATEGORY_NONE) {
                moveToMenu?.let {
                    val wrapped = DrawableCompat.wrap(it.icon).mutate()
                    DrawableCompat.setTint(wrapped, ContextCompat.getColor(this, R.color.white))
                    it.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                }
            }
            screenshotInfoMenu = menu.findItem(R.id.action_screenshot_info)

            deleteMenu = menu.findItem(R.id.action_delete)

            selectAllMenu = menu.findItem(R.id.action_select_all)
            selectAllMenu?.isVisible = false
        }

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.action_share -> {
                showShareScreenshotDialog(this, screenshots[view_pager.currentItem])
                TelemetryWrapper.shareScreenshot()
            }
            R.id.action_move_to -> {
                startActivity(SortingPanelActivity.sortOldScreenshot(this,
                        screenshots[view_pager.currentItem]))
            }
            R.id.action_screenshot_info -> {
                showScreenshotInfoDialog(this, screenshots[view_pager.currentItem])
            }
            R.id.action_delete -> {
                showDeleteScreenshotDialog(this, screenshots[view_pager.currentItem],
                        object : OnDeleteScreenshotListener {
                            override fun onDeleteScreenshot() {
                                finish()
                            }
                        })
            }
            R.id.action_select_all -> {
                selectAllBlocks()
                TelemetryWrapper.selectAllExtractedText()
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return super.onOptionsItemSelected(item)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initActionBar() {
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        toolbar.setNavigationOnClickListener {
            onBackPressed()
        }

        toolbar.setOnTouchListener { _, event ->
            routeUnhandledEventToOverlay(event)
        }

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowTitleEnabled(false)
        }
    }

    /* Toolbar always eat all touch events regardless they are handled or not, workaround here to
     * dispatch touch event to underlying graphic overlay */
    private fun routeUnhandledEventToOverlay(event: MotionEvent): Boolean {
        val scale = IMAGE_SCALE_TEXT_MODE
        val leftDiff = view_pager.measuredWidth * (1 - scale) / 2f
        val x = (event.x - leftDiff) / scale
        val y = event.y / scale + (toolbar.layoutParams as ViewGroup.MarginLayoutParams).topMargin
        event.setLocation(x, y)
        return graphicOverlay.dispatchTouchEvent(event)
    }

    private fun initViewPager() {
        launch(Dispatchers.Main) {
            screenshots = getScreenshots().sortedByDescending { it.lastModified }
            view_pager.adapter = adapter.apply {
                screenshots = this@DetailPageActivity.screenshots
                itemCallback = this@DetailPageActivity.itemCallback
                imageStateCallback = this@DetailPageActivity.imageStateCallback
            }
            view_pager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
                override fun onPageSelected(position: Int) {
                    hasRunOcr = false
                }
            })
            view_pager.currentItem = screenshots.indexOfFirst { it.id == screenshotId }
        }
    }

    private fun initFab() {
        val fab = findViewById<FloatingActionButton>(R.id.text_mode_fab)

        val fabListener = View.OnClickListener {
            when (it.id) {
                R.id.text_mode_fab -> {
                    isRecognizing = true
                    startRecognition()
                    TelemetryWrapper.extractTextFromScreenshot()
                }

                R.id.cancel_fab -> {
                    isRecognizing = false
                    updateUI()
                }
            }
        }

        fab.setOnClickListener(fabListener)
        cancel_fab.setOnClickListener(fabListener)
    }

    private fun initPanel() {
        BottomSheetBehavior.from(text_mode_panel_content)
                .setBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
                    override fun onSlide(bottomSheet: View, slideOffset: Float) {

                    }

                    override fun onStateChanged(bottomSheet: View, newState: Int) {
                        if (newState == BottomSheetBehavior.STATE_COLLAPSED) {
                            unselectAllBlocks()
                            switchToHintModePanelLayout()
                        }

                        if (newState == BottomSheetBehavior.STATE_EXPANDED
                                && graphicOverlayHelper.getSelectedText() == "") {
                            BottomSheetBehavior.from(text_mode_panel_content).state = BottomSheetBehavior.STATE_COLLAPSED
                        }
                    }
                })

        textModePanelTextView.movementMethod = BetterLinkMovementMethod.newInstance().setOnLinkClickListener { _, _ ->
            TelemetryWrapper.clickLinkInExtractedText()
            false
        }

        textModePanelHint.setOnClickListener { TelemetryWrapper.clickOnOCRBottomTip() }
    }

    private fun startRecognition() {
        val appContext = applicationContext
        launch(Dispatchers.Main) {
            updateUI()

            val screenshot = screenshots[view_pager.currentItem]
            val result = withContext(Dispatchers.Default) {
                runTextRecognition(screenshot)
            }

            if (result is Result.Success) {
                if (result is Result.WeiredImageSize) {
                    ScryerToast.makeText(this@DetailPageActivity,
                            getString(R.string.detail_ocr_error_edgecase),
                            Toast.LENGTH_SHORT).show()
                    TelemetryWrapper.viewTextInScreenshot(textRecognitionResultForTelemetry(TelemetryWrapper.Value.WEIRD_SIZE, result.value))
                } else {
                    TelemetryWrapper.viewTextInScreenshot(textRecognitionResultForTelemetry(TelemetryWrapper.Value.SUCCESS, result.value))
                }

                writeContentTextToDb(screenshot, result.value.text)

                if (isRecognizing) {
                    isTextMode = true
                    processTextRecognitionResult(result.value)
                    updateUI()
                    if (!hasRunOcr) {
                        hasRunOcr = true
                        Promoter.onOcrButtonClicked(appContext)
                    }
                }

            } else if (result is Result.Unavailable) {
                showConnectPromptSnackbar()
                TelemetryWrapper.viewTextInScreenshot(TelemetryWrapper.TextRecognitionResult(TelemetryWrapper.Value.FAIL, result.msg))

            } else if (result is Result.Failed) {
                ScryerToast.makeText(this@DetailPageActivity,
                        getString(R.string.detail_ocr_error_failed),
                        Toast.LENGTH_SHORT).show()

                TelemetryWrapper.viewTextInScreenshot(TelemetryWrapper.TextRecognitionResult(TelemetryWrapper.Value.FAIL, result.msg))
            }

            isRecognizing = false
            updateUI()
        }
    }

    private fun writeContentTextToDb(screenshot: ScreenshotModel, contentText: String) {
        launch(Dispatchers.IO + NonCancellable) {
            screenshot.contentText = contentText
            viewModel.updateScreenshot(screenshot)
        }
    }

    private fun textRecognitionResultForTelemetry(value: String, textResult: FirebaseVisionText): TelemetryWrapper.TextRecognitionResult {
        val regex = "http://|https://".toRegex()
        val match = regex.find(textResult.text)

        val textBlocks = textResult.textBlocks.size
        val totalLength = textResult.text.length

        return TelemetryWrapper.TextRecognitionResult(value, "",
                match?.groupValues?.size ?: 0, textBlocks, totalLength)
    }

    private fun showConnectPromptSnackbar() {
        Snackbar.make(snackbar_container, R.string.detail_ocr_error_module, Snackbar.LENGTH_LONG).apply {
            setAction(R.string.detail_ocr_error_action_connect) {
                startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                TelemetryWrapper.clickOnOCRErrorTip(getString(R.string.detail_ocr_error_module))
            }
            show()
        }
    }

    private suspend fun runTextRecognition(screenshot: ScreenshotModel): Result {
        val decoded = try {
            BitmapFactory.decodeFile(screenshot.absolutePath)
        } catch (e: Error) {
            return Result.Failed("decode failed: " + e.message)
        }

        return decoded?.let { bitmap ->
            try {
                runTextRecognition(bitmap)?.let { result ->
                    if (isValidSize(bitmap)) {
                        Result.Success(result)
                    } else {
                        Result.WeiredImageSize(result,
                                "weird image size: ${bitmap.width}x${bitmap.height}")
                    }
                }
            } catch (e: Exception) {
                if ((e as? FirebaseMLException)?.code == FirebaseMLException.UNAVAILABLE) {
                    Result.Unavailable("recognize failed: " + e.message)
                } else {
                    Result.Failed("recognize failed: " + e.message)
                }
            }

        } ?: Result.Failed("invalid bitmap")
    }

    private fun isValidSize(bitmap: Bitmap): Boolean {
        return if (bitmap.width >= bitmap.height) {
            isValidLandscapeSize(bitmap)
        } else {
            isValidPortraitSize(bitmap)
        }
    }

    private fun isValidPortraitSize(bitmap: Bitmap): Boolean {
        val isWidthValid = bitmap.width <= 1.5f * screenSize.width()
        val isHeightValid = bitmap.height <= 2 * screenSize.height()
        return isWidthValid && isHeightValid
    }

    private fun isValidLandscapeSize(bitmap: Bitmap): Boolean {
        val isWidthValid = bitmap.width <= screenSize.height()
        val isHeightValid = bitmap.height <= 2 * screenSize.width()
        return isWidthValid && isHeightValid
    }

    private fun updateUI() {
        val pagerScale: Float
        val pagerTranslation: Float
        val pageView = adapter.findViewForPosition(view_pager.currentItem)

        if (isTextMode) {
            pageView?.resetScale()

            updateFabUI(true, false)
            enableTextModeMenu(true)
            pagerScale = IMAGE_SCALE_TEXT_MODE
            pagerTranslation = -view_pager.height * ((1 - IMAGE_SCALE_TEXT_MODE) / 2f)

            launch(Dispatchers.Main) {
                setupTextSelectionCallback(textModePanelTextView)
                updateLoadingViewVisibility(false)
                updateTextModePanelVisibility(true)
            }

        } else {
            updateLoadingViewVisibility(isRecognizing)
            updateFabUI(false, isRecognizing)
            updateTextModePanelVisibility(false)
            enableTextModeMenu(false)
            pagerScale = IMAGE_SCALE_NORMAL_MODE
            pagerTranslation = 1f
            updateGraphicOverlay(emptyList())
        }
        updateNavigationIcon()

        if (isRecognizing) {
            supportActionBar?.hide()
        } else {
            supportActionBar?.show()
        }

        view_pager.pageLocked = (pageView?.isScaled() == true)
        listOf(view_pager, graphicOverlay).forEach {
            it.animate()
                    .scaleX(pagerScale)
                    .scaleY(pagerScale)
                    .translationY(pagerTranslation).duration = 150
        }
    }

    private fun updateLoadingViewVisibility(visible: Boolean) {
        if (visible) {
            loadingViewController.show()
        } else {
            loadingViewController.hide()
        }
    }

    private fun updateFabUI(isTextMode: Boolean, isLoading: Boolean) {
        when {
            isTextMode -> {
                cancel_fab.hide()
                text_mode_fab.hide()
            }

            isLoading -> {
                cancel_fab.show()
                text_mode_fab.hide()
            }

            else -> {
                cancel_fab.hide()
                text_mode_fab.show()
            }
        }
    }

    private fun updateTextModePanelVisibility(visible: Boolean) {
        val visibility = if (visible) {
            BottomSheetBehavior.from(text_mode_panel_content).state = BottomSheetBehavior.STATE_COLLAPSED
            View.VISIBLE
        } else {
            View.GONE
        }
        text_mode_panel.visibility = visibility
    }

    private fun enableTextModeMenu(enable: Boolean) {
        shareMenu?.isVisible = !enable
        moveToMenu?.isVisible = !enable
        screenshotInfoMenu?.isVisible = !enable
        deleteMenu?.isVisible = !enable

        selectAllMenu?.isVisible = enable
    }

    private fun updateNavigationIcon() {
        toolbar.navigationIcon = ContextCompat.getDrawable(this, if (isTextMode) {
            R.drawable.close_large
        } else {
            R.drawable.back
        })
    }

    @Suppress("ConstantConditionIf")
    private suspend fun getScreenshots(): List<ScreenshotModel> {
        return withContext(Dispatchers.Default) {
            if (SUPPORT_SLIDE) {
                srcCollectionId?.let {
                    val list = if (it == CollectionModel.CATEGORY_NONE) {
                        listOf(it, CollectionModel.UNCATEGORIZED)
                    } else {
                        listOf(it)
                    }
                    viewModel.getScreenshotList(list)
                } ?: viewModel.getScreenshotList()
            } else {
                viewModel.getScreenshot(screenshotId)?.let { listOf(it) } ?: emptyList()
            }
        }
    }

    private fun toggleActionBar() {
        var isShowing = supportActionBar?.isShowing ?: false
        isShowing = !isShowing

        if (isShowing) {
            toolbar_background.visibility = View.VISIBLE
            supportActionBar?.show()
            window?.decorView?.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            text_mode_fab.show()
            cancel_fab.hide()

        } else {
            toolbar_background.visibility = View.INVISIBLE
            supportActionBar?.hide()
            window?.decorView?.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN

            text_mode_fab.hide()
            cancel_fab.hide()
        }
    }

    private suspend fun runTextRecognition(
            selectedImage: Bitmap
    ): FirebaseVisionText? = suspendCoroutine { cont ->

        val image = FirebaseVisionImage.fromBitmap(selectedImage)
        val detector = FirebaseVision.getInstance().onDeviceTextRecognizer
        detector.processImage(image)
                .addOnSuccessListener { texts ->
                    cont.resume(texts)
                }
                .addOnFailureListener { exception ->
                    cont.resumeWithException(exception)
                }
    }

    private fun processTextRecognitionResult(result: FirebaseVisionText) {
        val pageView = adapter.findViewForPosition(view_pager.currentItem) ?: return

        val graphicBlocks = graphicOverlayHelper.convertToGraphicBlocks(result, pageView)
//        val graphicBlocks = graphicOverlayHelper.convertWordsToGraphicBlocks(result,
//                listOf("word1", "word2"),
//                pageView)
        updateGraphicOverlay(graphicBlocks)

        switchToHintModePanelLayout()
        updatePanel("")
    }

    private fun updateGraphicOverlay(blocks: List<TextBlockGraphic>) {
        graphicOverlay.setBlocks(blocks, if (isTextMode) {
            GraphicOverlay.MODE_OVERLAY_HIGHLIGHT
        } else {
            GraphicOverlay.MODE_HIGHLIGHT
        })

        graphicOverlayHelper.blocks = blocks

        val touchHelper = GraphicOverlayTouchHelper(this, blocks)
        touchHelper.callback = object : GraphicOverlayTouchHelper.Callback {
            override fun onBlockSelectStateChanged(block: TextBlockGraphic?) {
                val selectedText = graphicOverlayHelper.getSelectedText()
                updatePanel(selectedText)

                if (!selectedText.isEmpty()) {
                    TelemetryWrapper.clickOnTextBlock()
                }
            }
        }
        graphicOverlay.setOnTouchListener { _, event ->
            touchHelper.onTouchEvent(event)
        }
    }

    private fun selectAllBlocks() {
        graphicOverlayHelper.selectAllBlocks()
        updatePanel(graphicOverlayHelper.getSelectedText())
    }

    private fun unselectAllBlocks() {
        graphicOverlayHelper.unselectAllBlocks()
    }

    private suspend fun setupTextSelectionCallback(textView: TextView) {
        return withContext(Dispatchers.Default) {
            val searchEngineManager = SearchEngineManager(listOf(
                    AssetsSearchEngineProvider(LocaleSearchLocalizationProvider())))
            val engine = searchEngineManager.getDefaultSearchEngine(this@DetailPageActivity)
            textView.customSelectionActionModeCallback = TextSelectionCallback(
                    textView,
                    object : TextSelectionCallback.SearchEngineDelegate {
                        override val name: String
                            get() = engine.name

                        override fun buildSearchUrl(text: String): String {
                            return engine.buildSearchUrl(text)
                        }
                    })
        }
    }

    private fun updatePanel(panelText: String) {
        textModePanelTextView.autoLinkMask = Linkify.ALL
        textModePanelTextView.text = panelText

        val behavior = BottomSheetBehavior.from(text_mode_panel_content)
        if (panelText.isEmpty()) {
            behavior.state = BottomSheetBehavior.STATE_COLLAPSED
        } else {
            switchToTextModePanelLayout()
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    private fun switchToHintModePanelLayout() {
        textModePanelHandler.visibility = View.GONE
        textModePanelTextView.visibility = View.GONE
        textModePanelHint.visibility = View.VISIBLE
    }

    private fun switchToTextModePanelLayout() {
        textModePanelHandler.visibility = View.VISIBLE
        textModePanelTextView.visibility = View.VISIBLE
        textModePanelHint.visibility = View.GONE
    }

//    private fun showSystemUI() {
//        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
//                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
//                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
//    }
//
//    private fun hideSystemUI() {
//        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
//                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
//                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
//                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
//                View.SYSTEM_UI_FLAG_LOW_PROFILE or
//                View.SYSTEM_UI_FLAG_FULLSCREEN or
//                View.SYSTEM_UI_FLAG_IMMERSIVE
//    }


    private fun showOcrOnboarding() {
        onboarding_view.visibility = View.VISIBLE
        onboarding_view.setOnClickListener {
            (onboarding_view.parent as ViewGroup).removeView(onboarding_view)
        }

        onboarding_overlay.overlayMode = GraphicOverlay.MODE_HIGHLIGHT
        onboarding_overlay.overlayColor = ContextCompat.getColor(this, R.color.detail_onboarding_overlay)
        onboarding_overlay.add(object : GraphicOverlay.Graphic(onboarding_overlay) {
            private val spotlightPaint: Paint = Paint().apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            }

            override fun draw(canvas: Canvas) {
                canvas.drawCircle((text_mode_fab.left + text_mode_fab.right) / 2f,
                        (text_mode_fab.top + text_mode_fab.bottom) / 2f,
                        (text_mode_fab.right - text_mode_fab.left).toFloat(),
                        spotlightPaint)
            }
        })

        prefs.setOcrOnboardingShown()
    }

    private class LoadingViewGroup(private val activity: DetailPageActivity) {
        fun show() {
            activity.apply {
                loading_overlay.visibility = View.VISIBLE
                loading_progress.visibility = View.VISIBLE
                loading_text.visibility = View.VISIBLE
            }
        }

        fun hide() {
            activity.apply {
                loading_overlay.visibility = View.INVISIBLE
                loading_progress.visibility = View.INVISIBLE
                loading_text.visibility = View.INVISIBLE
            }
        }
    }

    sealed class Result {
        open class Success(val value: FirebaseVisionText) : Result()
        class WeiredImageSize(
                value: FirebaseVisionText,
                @Suppress("unused") val msg: String
        ) : Success(value)
        open class Failed(@Suppress("unused") val msg: String) : Result()
        class Unavailable(msg: String) : Failed(msg)
    }

}
