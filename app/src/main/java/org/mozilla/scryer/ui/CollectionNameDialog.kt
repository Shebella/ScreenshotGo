package org.mozilla.scryer.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import kotlinx.coroutines.experimental.Dispatchers
import kotlinx.coroutines.experimental.GlobalScope
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.withContext
import org.mozilla.scryer.R
import org.mozilla.scryer.persistence.CollectionModel
import org.mozilla.scryer.util.CollectionListHelper
import org.mozilla.scryer.util.launchIO
import org.mozilla.scryer.viewmodel.ScreenshotViewModel
import java.util.*

class CollectionNameDialog(private val context: Context,
                           private val delegate: Delegate) {

    companion object {
        /**
         * @param excludeSuggestion: whether to take suggest collection names into consideration when
         * searching for conflict name
         */
        fun createNewCollection(context: Context, viewModel: ScreenshotViewModel,
                                excludeSuggestion: Boolean,
                                callback: ((collection: CollectionModel) -> Unit)? = null) {
            GlobalScope.launch(Dispatchers.Main) {
                showNewCollectionDialog(context, viewModel, excludeSuggestion,
                        queryCollectionList(viewModel), callback)
            }
        }

        fun renameCollection(context: Context, viewModel: ScreenshotViewModel, collectionId: String?) {
            GlobalScope.launch(Dispatchers.Main) {
                val collections = queryCollectionList(viewModel)
                collections.find { it.id == collectionId }?.let {
                    showRenameDialog(context, viewModel, it, collections)
                }
            }
        }

        private fun showNewCollectionDialog(context: Context, viewModel: ScreenshotViewModel,
                                            excludeSuggestion: Boolean,
                                            collections: List<CollectionModel>,
                                            callback: ((collection: CollectionModel) -> Unit)?) {
            val dialog = CollectionNameDialog(context, object : CollectionNameDialog.Delegate {

                override fun onPositiveAction(collectionName: String) {
                    GlobalScope.launch(Dispatchers.Main) {
                        val result = updateOrInsertCollection(context, collectionName, viewModel, collections)
                        callback?.invoke(result)
                    }
                }

                override fun isNameConflict(name: String): Boolean {
                    return CollectionListHelper.isNameConflict(name, collections, excludeSuggestion)
                }

                override fun getPositiveButtonText(): Int {
                    return R.string.dialogue_action_add
                }

                override fun getNegativeButtonText(): Int {
                    return android.R.string.cancel
                }
            })

            dialog.title = context.resources.getText(R.string.dialogue_title_collection).toString()
            dialog.show()
        }

        private fun showRenameDialog(context: Context, viewModel: ScreenshotViewModel,
                                     collection: CollectionModel,
                                     collections: List<CollectionModel>) {
            val originalName = collection.name

            val dialog = CollectionNameDialog(context, object : CollectionNameDialog.Delegate {
                override fun onPositiveAction(collectionName: String) {
                    collection.name = collectionName
                    launchIO {
                        viewModel.updateCollection(collection)
                    }
                }

                override fun isNameConflict(name: String): Boolean {
                    val isOriginalName = name.compareTo(originalName, true) == 0
                    return !isOriginalName
                            && CollectionListHelper.isNameConflict(name, collections, true)
                }

                override fun getPositiveButtonText(): Int {
                    return android.R.string.ok
                }

                override fun getNegativeButtonText(): Int {
                    return android.R.string.cancel
                }
            })

            dialog.title = context.resources.getText(R.string.dialogue_rename_title_rename).toString()
            dialog.originalName = originalName
            dialog.show()
        }

        private suspend fun updateOrInsertCollection(
                context: Context,
                name: String,
                viewModel: ScreenshotViewModel,
                collections: List<CollectionModel>
        ): CollectionModel = withContext(Dispatchers.Main) {
            collections.find {
                it.name.equals(name, true)

            }?.let {
                withContext(Dispatchers.IO) {
                    it.createdDate = System.currentTimeMillis()
                    viewModel.updateCollectionId(it, UUID.randomUUID().toString())
                }
                it

            }?: run {
                val color = CollectionListHelper.nextCollectionColor(context, collections, true)
                val model = CollectionModel(name, System.currentTimeMillis(), color)
                withContext(Dispatchers.IO) {
                    viewModel.addCollection(model)
                }
                model
            }
        }

        private suspend fun queryCollectionList(viewModel: ScreenshotViewModel): List<CollectionModel> {
            return withContext(Dispatchers.IO) {
                viewModel.getCollectionList()
            }
        }
    }

    private val dialog: AlertDialog
    private val validator: InputValidator

    private val dialogView: View by lazy {
        View.inflate(context, R.layout.dialog_collection_name, null)
    }
    private val titleText: TextView by lazy { dialogView.findViewById<TextView>(R.id.title) }
    private val editText: EditText by lazy { dialogView.findViewById<EditText>(R.id.edit_text) }
    private val editTextBar: View by lazy { dialogView.findViewById<View>(R.id.edit_text_bar) }
    private val errorIcon: View by lazy { dialogView.findViewById<View>(R.id.edit_text_icon) }
    private val errorText: TextView by lazy { dialogView.findViewById<TextView>(R.id.error_text) }

    private var title: String = ""
    private var originalName = ""
    private val inputName: String
        get() = editText.text.toString()

    private val validIcon: Drawable? by lazy {
        null
    }

    private val invalidIcon: Drawable? by lazy {
        ContextCompat.getDrawable(context, R.drawable.error)?.let {
            val wrapped = DrawableCompat.wrap(it)
            DrawableCompat.setTint(wrapped, ContextCompat.getColor(context, R.color.errorRed))
            wrapped
        }
    }

    init {
        dialog = createDialog()
        validator = createValidator()

        dialog.setOnShowListener {
            initDialogContent()
        }

        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                validator.validate(s.toString())
            }

            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun createDialog(): AlertDialog {
        return AlertDialog.Builder(context, R.style.Theme_AppCompat_Light_Dialog_Alert)
                .setPositiveButton(delegate.getPositiveButtonText()) { _, _ ->
                    if (inputName == originalName) {
                        delegate.onNegativeAction()
                    } else {
                        delegate.onPositiveAction(inputName)
                    }
                }
                .setNegativeButton(delegate.getNegativeButtonText()) { _, _ ->
                    delegate.onNegativeAction()
                }
                .setView(dialogView)
                .create()
    }

    private fun createValidator(): InputValidator {
        return InputValidator(context, object : InputValidator.ViewDelegate {

            override fun forbidContinue(forbid: Boolean) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = !forbid
            }

            override fun onErrorStatusUpdate(errorMsg: String) {
                errorIcon.background = if (errorMsg.isEmpty()) { validIcon } else { invalidIcon }
                errorText.text = errorMsg

                editTextBar.setBackgroundColor(if (errorMsg.isEmpty()) {
                    ContextCompat.getColor(context, R.color.primaryTeal)
                } else {
                    ContextCompat.getColor(context, R.color.errorRed)
                })
            }

            override fun isCollectionExist(name: String): Boolean {
                return delegate.isNameConflict(name)

            }
        })
    }

    fun show() {
        showImmediately()
    }

    private fun showImmediately() {
        dialog.show()
        editText.requestFocus()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    }

    private fun initDialogContent() {
        titleText.text = title

        if (originalName.isNotEmpty()) {
            editText.setText(originalName)
            editText.setSelection(0, originalName.length)
        }

        validator.validate(inputName)

        val colors = ContextCompat.getColorStateList(context, R.color.primary_text_button)
        this.dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(colors)
        this.dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(colors)
    }

    private class InputValidator(private val context: Context, private val viewDelegate: ViewDelegate) {
        private val lengthLimit = context.resources.getInteger(
                R.integer.collection_name_dialog_max_input_length)

        fun validate(input: String) {
            when {
                input.isEmpty() -> {
                    viewDelegate.forbidContinue(true)
                }

                input.length > lengthLimit -> {
                    viewDelegate.forbidContinue(true)
                    viewDelegate.onErrorStatusUpdate(context.getString(
                            R.string.dialogue_rename_error_maximum))
                }

                viewDelegate.isCollectionExist(input) -> {
                    viewDelegate.forbidContinue(true)
                    viewDelegate.onErrorStatusUpdate(context.getString(
                            R.string.dialogue_rename_error_duplicate))
                }

                else -> {
                    viewDelegate.forbidContinue(false)
                    viewDelegate.onErrorStatusUpdate("")
                }
            }
        }

        interface ViewDelegate {
            fun forbidContinue(forbid: Boolean)
            fun onErrorStatusUpdate(errorMsg: String)
            fun isCollectionExist(name: String): Boolean
        }
    }

    interface Delegate {
        fun onPositiveAction(collectionName: String)
        fun onNegativeAction() {}
        fun isNameConflict(name: String): Boolean
        fun getPositiveButtonText(): Int
        fun getNegativeButtonText(): Int
    }
}
