package app.simple.felicity.dialogs.shiroikuma

import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import app.simple.felicity.databinding.DialogSkRgbaPickerBinding
import app.simple.felicity.decorations.seekbars.FelicitySeekbar
import app.simple.felicity.extensions.dialogs.ScopedBottomSheetFragment
import app.simple.felicity.preferences.ShiroikumaPreferences
import java.util.Locale

/**
 * 白い熊 音楽 UI: ARGB color picker with four channel sliders (R/G/B/A), a live
 * old-vs-new preview, and one-click boxes prefilled with recently picked colors.
 *
 * Returns the picked color through [onColorPicked]; null means "reset this slot
 * to its inherited default".
 */
class SkRgbaColorPicker : ScopedBottomSheetFragment() {

    private lateinit var binding: DialogSkRgbaPickerBinding

    var onColorPicked: ((Int?) -> Unit)? = null

    private var initialColor = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = DialogSkRgbaPickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initialColor = requireArguments().getInt(ARG_COLOR)
        binding.pickerTitle.text = requireArguments().getString(ARG_TITLE)

        binding.oldColor.background = swatchDrawable(initialColor)
        setSliders(initialColor)
        updatePreview()

        val listener = object : FelicitySeekbar.OnSeekChangeListener {
            override fun onProgressChanged(seekbar: FelicitySeekbar, progress: Float, fromUser: Boolean) {
                if (fromUser) {
                    updatePreview()
                }
            }
        }

        binding.seekRed.setOnSeekChangeListener(listener)
        binding.seekGreen.setOnSeekChangeListener(listener)
        binding.seekBlue.setOnSeekChangeListener(listener)
        binding.seekAlpha.setOnSeekChangeListener(listener)

        setupRecentColors()

        binding.buttonApply.setOnClickListener {
            val color = currentColor()
            ShiroikumaPreferences.addRecentColor(color)
            onColorPicked?.invoke(color)
            dismiss()
        }

        binding.buttonCancel.setOnClickListener {
            dismiss()
        }

        binding.buttonDefault.setOnClickListener {
            onColorPicked?.invoke(null)
            dismiss()
        }
    }

    private fun setupRecentColors() {
        val recents = ShiroikumaPreferences.getRecentColors()

        if (recents.isEmpty()) {
            binding.recentColorsRow.visibility = View.GONE
            return
        }

        val size = dp(28)
        val margin = dp(4)

        recents.forEach { color ->
            val box = View(requireContext())
            val params = LinearLayout.LayoutParams(size, size)
            params.marginEnd = margin
            box.layoutParams = params
            box.background = swatchDrawable(color)
            box.setOnClickListener {
                setSliders(color)
                updatePreview()
            }
            binding.recentColorsRow.addView(box)
        }
    }

    private fun setSliders(color: Int) {
        binding.seekRed.setProgress(Color.red(color).toFloat())
        binding.seekGreen.setProgress(Color.green(color).toFloat())
        binding.seekBlue.setProgress(Color.blue(color).toFloat())
        binding.seekAlpha.setProgress(Color.alpha(color).toFloat())
    }

    private fun currentColor(): Int {
        return Color.argb(
                binding.seekAlpha.getProgress().toInt().coerceIn(0, 255),
                binding.seekRed.getProgress().toInt().coerceIn(0, 255),
                binding.seekGreen.getProgress().toInt().coerceIn(0, 255),
                binding.seekBlue.getProgress().toInt().coerceIn(0, 255))
    }

    private fun updatePreview() {
        val color = currentColor()
        binding.newColor.background = swatchDrawable(color)
        binding.hexValue.text = String.format(Locale.ROOT, "#%08X", color)
        binding.valueRed.text = Color.red(color).toString()
        binding.valueGreen.text = Color.green(color).toString()
        binding.valueBlue.text = Color.blue(color).toString()
        binding.valueAlpha.text = Color.alpha(color).toString()
    }

    private fun swatchDrawable(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(6).toFloat()
            setColor(color)
            setStroke(dp(1), 0x66888888)
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        /* no-op */
    }

    companion object {
        private const val ARG_TITLE = "title"
        private const val ARG_COLOR = "color"

        const val TAG = "SkRgbaColorPicker"

        fun newInstance(title: String, initialColor: Int): SkRgbaColorPicker {
            val args = Bundle()
            args.putString(ARG_TITLE, title)
            args.putInt(ARG_COLOR, initialColor)
            val fragment = SkRgbaColorPicker()
            fragment.arguments = args
            return fragment
        }
    }
}
