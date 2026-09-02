package sa.arsel.core.inapp

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.text.InputType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sa.arsel.core.notification.NotificationImage
import sa.arsel.core.log.ArselLog

/**
 * Draws an in-app message into the Activity currently on screen.
 *
 * Views are built in code rather than inflated from XML on purpose: a library that ships layout
 * resources collides with the host app's resource names and forces every integrator to carry them.
 * A handful of `View` constructions costs less than that.
 *
 * The message is attached to the Activity's own `android.R.id.content`, not a new Window, so it
 * inherits that Activity's lifecycle — it cannot outlive the screen it was shown on, and there is
 * no window token to leak.
 */
/** One input's identity and how to read it. Empty means unanswered, whatever the control. */
private class FieldReader(
    val fieldId: String,
    val required: Boolean,
    val read: () -> String,
    val focus: () -> Unit,
)

/** Layouts that dim the app behind them. Banners deliberately do not. */
private val SCRIMMED_LAYOUTS =
    setOf(
        LAYOUT_MODAL,
        LAYOUT_FULLSCREEN,
        LAYOUT_HALF_INTERSTITIAL,
        LAYOUT_ALERT,
        LAYOUT_FORM,
        LAYOUT_RATING,
    )

internal class InAppPresenter(
    private val controller: InAppController,
    private val activityProvider: () -> Activity?,
    private val log: ArselLog,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val main = Handler(Looper.getMainLooper())

    fun present(message: InAppMessage) {
        val delayMs = message.delaySeconds * MILLIS_PER_SECOND
        if (delayMs <= 0L) {
            main.post { show(message) }
            return
        }
        main.postDelayed({ show(message) }, delayMs)
    }

    private fun show(message: InAppMessage) {
        val activity = activityProvider()
        // Backgrounded during the delay window, or a host with no Activity at all. Abandoned
        // silently: no beacon and no counter, because a message nobody saw is not an impression
        // and recording one corrupts every rate in the channel.
        if (activity == null || activity.isFinishing) {
            controller.releaseActive()
            return
        }
        val root = runCatching { activity.findViewById<ViewGroup>(android.R.id.content) }.getOrNull()
        if (root == null) {
            controller.releaseActive()
            return
        }
        runCatching { render(activity, root, message) }
            .onFailure {
                log.w("in-app render failed", it)
                controller.releaseActive()
            }
    }

    private fun render(
        activity: Activity,
        root: ViewGroup,
        message: InAppMessage,
    ) {
        val shownAtMs = clock()
        val density = activity.resources.displayMetrics.density
        val scrimmed = message.layout in SCRIMMED_LAYOUTS

        val overlay = FrameLayout(activity)
        if (scrimmed) {
            overlay.setBackgroundColor(SCRIM_COLOR)
            overlay.isClickable = true
        }

        val panelColor = parseColor(message.backgroundColor) ?: Color.WHITE
        val textColor = parseColor(message.textColor) ?: contrastTo(panelColor)
        val panel = buildPanel(activity, message, density, panelColor)
        overlay.addView(panel, panelLayout(message, density))

        var closed = false
        val close = { reportDismiss: Boolean ->
            if (!closed) {
                closed = true
                runCatching { root.removeView(overlay) }
                controller.releaseActive()
                if (reportDismiss) {
                    controller.recordDismiss(message, (clock() - shownAtMs) / MILLIS_PER_SECOND)
                }
            }
        }

        if (message.showCloseButton) {
            panel.addView(closeButton(activity, density, textColor) { close(true) })
            // Dismissable by the scrim only when the author allowed a close affordance; otherwise a
            // stray tap destroys a message they meant to be deliberate.
            if (scrimmed) overlay.setOnClickListener { close(true) }
        }
        val readAnswers =
            if (message.layout in INPUT_LAYOUTS && message.fields.isNotEmpty()) {
                addFields(activity, panel, message, density, textColor)
            } else {
                null
            }

        addButtons(activity, panel, message, density) { button ->
            // A form's non-dismiss button submits. Answers are read BEFORE close() detaches the
            // inputs, and a failed validation aborts the tap entirely so the message stays open
            // with the problem visible.
            if (readAnswers != null && button.action != ACTION_DISMISS) {
                val answers = readAnswers() ?: return@addButtons
                controller.recordSubmit(message, answers)
            }
            if (button.action != ACTION_DISMISS) controller.recordClick(message, button.buttonId)
            close(button.action == ACTION_DISMISS)
            performAction(activity, button)
        }

        root.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
        // Reported once the view is actually attached, never at build time.
        overlay.post { controller.recordImpression(message, message.triggerEventName) }
    }

    private fun buildPanel(
        activity: Activity,
        message: InAppMessage,
        density: Float,
        panelColor: Int,
    ): LinearLayout {
        val textColor = parseColor(message.textColor) ?: contrastTo(panelColor)
        val padding = dp(PADDING_DP, density)

        val panel = LinearLayout(activity)
        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(padding, padding, padding, padding)
        panel.background =
            GradientDrawable().apply {
                setColor(panelColor)
                cornerRadius = dp(CORNER_DP, density).toFloat()
            }
        // Swallows taps, so one landing on the panel never reaches the dismissing scrim behind it.
        panel.isClickable = true

        // ALERT is the OS-alert shape: text and actions only, never an image.
        if (!message.imageUrl.isNullOrEmpty() && message.layout != LAYOUT_ALERT) {
            panel.addView(imageView(activity, message.imageUrl, density))
        }

        if (message.layout != LAYOUT_IMAGE_ONLY) {
            panel.addView(label(activity, message.headline, HEADLINE_SP, textColor, bold = true))
            if (message.body.isNotEmpty()) {
                val body = label(activity, message.body, BODY_SP, textColor, bold = false)
                body.setPadding(0, dp(GAP_DP, density), 0, 0)
                panel.addView(body)
            }
        }
        return panel
    }

    /**
     * An `ImageView` that fills in once the bitmap arrives.
     *
     * Loaded off the main thread and applied back on it: [NotificationImage] does blocking,
     * bounded network I/O for the FCM service thread, and calling it here would be a
     * `NetworkOnMainThreadException`. A failed load removes the view and KEEPS the message —
     * the headline and buttons still carry it, and a blank rectangle reads as a product bug in
     * a way that "no image" does not.
     */
    private fun imageView(
        activity: Activity,
        url: String,
        density: Float,
    ): ImageView {
        val view = ImageView(activity)
        view.adjustViewBounds = true
        view.scaleType = ImageView.ScaleType.FIT_CENTER
        view.layoutParams =
            LinearLayout.LayoutParams(MATCH, WRAP).apply {
                bottomMargin = dp(GAP_DP, density)
            }
        // Hidden until it has something to draw, so a slow network never leaves a gap in the
        // layout that the text then jumps past when it fills.
        view.visibility = android.view.View.GONE

        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { NotificationImage.load(url, log) }
            if (bitmap == null) {
                (view.parent as? ViewGroup)?.removeView(view)
                return@launch
            }
            view.setImageBitmap(bitmap)
            view.visibility = android.view.View.VISIBLE
        }
        return view
    }

    /** Text is always set from a value, never from markup: the content is org-authored and renders inside the customer's app. */
    private fun label(
        activity: Activity,
        value: String,
        sizeSp: Float,
        color: Int,
        bold: Boolean,
    ): TextView {
        val view = TextView(activity)
        view.text = value
        view.setTextColor(color)
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        if (bold) view.setTypeface(view.typeface, Typeface.BOLD)
        return view
    }

    private fun panelLayout(
        message: InAppMessage,
        density: Float,
    ): FrameLayout.LayoutParams {
        val margin = dp(MARGIN_DP, density)
        val gravity =
            when (message.layout) {
                LAYOUT_BANNER_TOP -> Gravity.TOP
                // Anchored to the bottom so the app stays visible above it, which is the whole
                // point of a half interstitial.
                LAYOUT_BANNER_BOTTOM, LAYOUT_HALF_INTERSTITIAL -> Gravity.BOTTOM
                else -> Gravity.CENTER
            }
        val height = if (message.layout == LAYOUT_FULLSCREEN) MATCH else WRAP
        val params = FrameLayout.LayoutParams(MATCH, height, gravity)
        params.setMargins(margin, margin, margin, margin)
        return params
    }

    /**
     * Draws the message's inputs and returns a reader for their answers.
     *
     * The reader returns null when a required field is unanswered, having focused the first
     * offender. Answers come back keyed by `fieldId`; this SDK never receives a destination, so
     * it cannot send one.
     */
    private fun addFields(
        activity: Activity,
        panel: LinearLayout,
        message: InAppMessage,
        density: Float,
        textColor: Int,
    ): () -> Map<String, String>? {
        val readers = ArrayList<FieldReader>(message.fields.size)

        for (field in message.fields) {
            val group = LinearLayout(activity)
            group.orientation = LinearLayout.VERTICAL
            group.setPadding(0, dp(GAP_DP, density), 0, 0)

            if (field.type != FIELD_CHECKBOX) {
                val caption = if (field.required) "${field.label} *" else field.label
                group.addView(label(activity, caption, BODY_SP, textColor, bold = false))
            }

            val reader = buildField(activity, group, field, density, textColor)
            panel.addView(group)
            readers.add(reader)
        }

        return {
            val answers = LinkedHashMap<String, String>(readers.size)
            var firstInvalid: (() -> Unit)? = null

            for (reader in readers) {
                val value = reader.read()
                if (value.isEmpty()) {
                    if (reader.required && firstInvalid == null) firstInvalid = reader.focus
                    continue
                }
                answers[reader.fieldId] = value
            }

            if (firstInvalid != null) {
                firstInvalid.invoke()
                null
            } else {
                answers
            }
        }
    }

    private fun buildField(
        activity: Activity,
        group: LinearLayout,
        field: InAppField,
        density: Float,
        textColor: Int,
    ): FieldReader =
        when (field.type) {
            FIELD_RATING -> buildRating(activity, group, field, density, textColor)
            FIELD_CHECKBOX -> buildCheckbox(activity, group, field, textColor)
            FIELD_DROPDOWN -> buildDropdown(activity, group, field)
            FIELD_RADIO -> buildRadio(activity, group, field, textColor)
            else -> buildTextInput(activity, group, field, textColor)
        }

    /**
     * Radio buttons rather than tappable labels: a rating is a single-choice control, and the
     * native widget brings the accessibility and keyboard behaviour a custom view would have to
     * reimplement.
     */
    private fun buildRating(
        activity: Activity,
        group: LinearLayout,
        field: InAppField,
        density: Float,
        textColor: Int,
    ): FieldReader {
        val scale = field.scale?.takeIf { it > 1 } ?: DEFAULT_RATING_SCALE
        val row = RadioGroup(activity)
        row.orientation = RadioGroup.HORIZONTAL

        for (value in 1..scale) {
            val option = RadioButton(activity)
            option.id = value
            // Stars up to five, numerals beyond: a ten-star row is unreadable at the width a
            // message gets, and NPS is conventionally numeric anyway.
            option.text = if (scale <= DEFAULT_RATING_SCALE) "★" else value.toString()
            option.setTextColor(textColor)
            option.minWidth = dp(MIN_TAP_TARGET_DP, density)
            row.addView(option)
        }
        group.addView(row)

        return FieldReader(
            fieldId = field.fieldId,
            required = field.required,
            read = { if (row.checkedRadioButtonId > 0) row.checkedRadioButtonId.toString() else "" },
            focus = { row.requestFocus() },
        )
    }

    private fun buildTextInput(
        activity: Activity,
        group: LinearLayout,
        field: InAppField,
        textColor: Int,
    ): FieldReader {
        val input = EditText(activity)
        input.setTextColor(textColor)
        input.setSingleLine(true)
        field.placeholder?.let { input.hint = it }
        input.inputType =
            when (field.type) {
                FIELD_EMAIL -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                FIELD_TEL -> InputType.TYPE_CLASS_PHONE
                else -> InputType.TYPE_CLASS_TEXT
            }
        group.addView(input)

        return FieldReader(
            fieldId = field.fieldId,
            required = field.required,
            read = { input.text.toString().trim() },
            focus = { input.requestFocus() },
        )
    }

    private fun buildCheckbox(
        activity: Activity,
        group: LinearLayout,
        field: InAppField,
        textColor: Int,
    ): FieldReader {
        val box = CheckBox(activity)
        box.text = if (field.required) "${field.label} *" else field.label
        box.setTextColor(textColor)
        group.addView(box)

        return FieldReader(
            fieldId = field.fieldId,
            required = field.required,
            // A required checkbox must be ticked, so an unticked one reads as empty rather than
            // as "false" — otherwise a consent box would pass validation while recording a refusal.
            read = { if (box.isChecked) "true" else if (field.required) "" else "false" },
            focus = { box.requestFocus() },
        )
    }

    private fun buildDropdown(
        activity: Activity,
        group: LinearLayout,
        field: InAppField,
    ): FieldReader {
        val spinner = Spinner(activity)
        val labels = ArrayList<String>(field.options.size + 1)
        labels.add(field.placeholder ?: "Choose…")
        field.options.forEach { labels.add(it.label) }

        spinner.adapter =
            ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, labels)
        group.addView(spinner)

        return FieldReader(
            fieldId = field.fieldId,
            required = field.required,
            // Index 0 is the placeholder row, so it reads as unanswered.
            read = {
                val index = spinner.selectedItemPosition - 1
                field.options.getOrNull(index)?.value.orEmpty()
            },
            focus = { spinner.requestFocus() },
        )
    }

    private fun buildRadio(
        activity: Activity,
        group: LinearLayout,
        field: InAppField,
        textColor: Int,
    ): FieldReader {
        val row = RadioGroup(activity)
        row.orientation = RadioGroup.VERTICAL

        field.options.forEachIndexed { index, option ->
            val button = RadioButton(activity)
            button.id = index + 1
            button.text = option.label
            button.setTextColor(textColor)
            row.addView(button)
        }
        group.addView(row)

        return FieldReader(
            fieldId = field.fieldId,
            required = field.required,
            read = {
                val index = row.checkedRadioButtonId - 1
                field.options.getOrNull(index)?.value.orEmpty()
            },
            focus = { row.requestFocus() },
        )
    }

    private fun addButtons(
        activity: Activity,
        panel: LinearLayout,
        message: InAppMessage,
        density: Float,
        onClick: (InAppButton) -> Unit,
    ) {
        if (message.buttons.isEmpty()) return
        val row = LinearLayout(activity)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(0, dp(GAP_DP, density), 0, 0)
        for (button in message.buttons) {
            val view = Button(activity)
            view.text = button.label
            view.minHeight = dp(MIN_TAP_TARGET_DP, density)
            view.setOnClickListener { onClick(button) }
            val params = LinearLayout.LayoutParams(0, WRAP, 1f)
            params.marginStart = dp(GAP_DP, density)
            row.addView(view, params)
        }
        panel.addView(row)
    }

    private fun closeButton(
        activity: Activity,
        density: Float,
        color: Int,
        onClose: () -> Unit,
    ): Button {
        val view = Button(activity)
        view.text = CLOSE_GLYPH
        view.contentDescription = CLOSE_LABEL
        view.setTextColor(color)
        view.minWidth = dp(MIN_TAP_TARGET_DP, density)
        view.minHeight = dp(MIN_TAP_TARGET_DP, density)
        view.setOnClickListener { onClose() }
        return view
    }

    /**
     * A deep link or URL leaves the app, so the click beacon is already queued by the caller before
     * this runs — the queue is persisted and survives the process going away.
     */
    private fun performAction(
        activity: Activity,
        button: InAppButton,
    ) {
        val value = button.value
        when (button.action) {
            ACTION_DEEP_LINK, ACTION_URL -> {
                if (value.isNullOrEmpty()) return
                runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value))) }
                    .onFailure { log.w("in-app: nothing on this device handles $value", it) }
            }
            ACTION_CUSTOM_EVENT ->
                if (!value.isNullOrEmpty()) {
                    controller.observe(TRIGGER_CUSTOM_EVENT, value, emptyMap())
                }
            else -> Unit
        }
    }

    private fun parseColor(hex: String?): Int? {
        if (hex.isNullOrEmpty()) return null
        return runCatching { Color.parseColor(hex) }.getOrNull()
    }

    /**
     * Supplies the readable half of a colour pair when an author set only the background —
     * otherwise a white-on-white message reports a perfectly healthy impression nobody could read.
     */
    private fun contrastTo(background: Int): Int {
        val luminance =
            (
                RED_WEIGHT * Color.red(background) +
                    GREEN_WEIGHT * Color.green(background) +
                    BLUE_WEIGHT * Color.blue(background)
            ) / MAX_CHANNEL
        return if (luminance > LIGHT_THRESHOLD) Color.BLACK else Color.WHITE
    }

    private fun dp(
        value: Int,
        density: Float,
    ): Int = (value * density).toInt()

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val MILLIS_PER_SECOND = 1000L

        /** ~45% black. Dark enough to read against, light enough to show the app behind it. */
        const val SCRIM_COLOR = 0x73000000
        const val PADDING_DP = 20
        const val MARGIN_DP = 16
        const val GAP_DP = 8
        const val CORNER_DP = 12

        /** The Material minimum touch target; anything smaller fails an accessibility scan. */
        const val MIN_TAP_TARGET_DP = 48
        const val HEADLINE_SP = 18f
        const val BODY_SP = 15f
        const val CLOSE_GLYPH = "×"
        const val CLOSE_LABEL = "Close"
        const val RED_WEIGHT = 0.2126
        const val GREEN_WEIGHT = 0.7152
        const val BLUE_WEIGHT = 0.0722
        const val MAX_CHANNEL = 255.0
        const val LIGHT_THRESHOLD = 0.6
    }
}
