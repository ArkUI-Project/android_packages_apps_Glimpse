/*
 * SPDX-FileCopyrightText: 2026 The ArkUI Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.home

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.RequestManager
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.signature.ObjectKey
import com.google.android.material.color.MaterialColors
import org.lineageos.glimpse.R
import com.google.android.material.R as MaterialR

internal fun Context.dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
internal fun Context.tone(attribute: Int) = MaterialColors.getColor(this, attribute, Color.GRAY)
internal fun Context.shape(color: Int, radius: Int = 24, stroke: Boolean = false) =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke) setStroke(dp(1), ColorUtils.setAlphaComponent(
            tone(MaterialR.attr.colorOnSurface), 18))
    }
internal fun View.clickableSurface(color: Int, radius: Int = 24) {
    background = RippleDrawable(ColorStateList.valueOf(ColorUtils.setAlphaComponent(
        context.tone(androidx.appcompat.R.attr.colorPrimary), 28)), context.shape(color, radius),
        context.shape(Color.WHITE, radius))
}
internal fun Context.label(value: String, size: Float, secondary: Boolean = false) = TextView(this).apply {
    text = value
    textSize = size
    setTextColor(tone(if (secondary) MaterialR.attr.colorOnSurfaceVariant else MaterialR.attr.colorOnSurface))
    includeFontPadding = false
    gravity = Gravity.CENTER_VERTICAL
}

internal data class GalleryRow(
    val id: String,
    val kind: Int,
    val title: String = "",
    val subtitle: String = "",
    val cover: GalleryPhoto? = null,
    val action: String = "",
    val icon: Int = R.drawable.ic_albums,
    val selected: Boolean = false,
    val selecting: Boolean = false,
) {
    companion object {
        const val SPACE = 0
        const val TITLE = 1
        const val SECTION = 2
        const val PHOTO = 3
        const val COMMON = 4
        const val ALBUM = 5
        const val PERSON = 6
        const val EMPTY = 7
        const val FOOTER = 8
    }
}

internal class SquareFrame(context: Context) : FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(
            MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY))
    }
}

internal class GalleryHomeAdapter(
    private val requests: RequestManager,
    private val click: (GalleryRow) -> Unit,
    private val hold: (GalleryRow) -> Unit,
) : ListAdapter<GalleryRow, GalleryHomeAdapter.Holder>(object : DiffUtil.ItemCallback<GalleryRow>() {
    override fun areItemsTheSame(old: GalleryRow, new: GalleryRow) = old.id == new.id
    override fun areContentsTheSame(old: GalleryRow, new: GalleryRow) = old == new
}) {
    class Holder(val root: View, val title: TextView? = null, val subtitle: TextView? = null,
        val image: ImageView? = null, val marker: ImageView? = null,
        val symbol: ImageView? = null) : RecyclerView.ViewHolder(root)

    override fun getItemViewType(position: Int) = getItem(position).kind

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val c = parent.context
        fun image() = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        fun margin(root: View, height: Int, horizontal: Int = 0, vertical: Int = 0) {
            root.layoutParams = RecyclerView.LayoutParams(-1, if (height < 0) height else c.dp(height)).apply {
                setMargins(c.dp(horizontal), c.dp(vertical), c.dp(horizontal), c.dp(vertical))
            }
        }
        return when (viewType) {
            GalleryRow.PHOTO -> {
                val root = SquareFrame(c)
                margin(root, -2, 0, 0)
                root.setPadding(c.dp(1), c.dp(1), c.dp(1), c.dp(1))
                val photo = image()
                root.addView(photo, FrameLayout.LayoutParams(-1, -1))
                val duration = c.label("", 12f).apply {
                    setTextColor(Color.WHITE)
                    setShadowLayer(c.dp(2).toFloat(), 0f, 1f, Color.BLACK)
                    setPadding(c.dp(5), c.dp(4), c.dp(5), c.dp(4))
                    background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(Color.TRANSPARENT, 0x66000000)).apply { cornerRadius = c.dp(4).toFloat() }
                }
                root.addView(duration, FrameLayout.LayoutParams(-1, c.dp(28), Gravity.BOTTOM))
                val marker = image().apply { imageTintList = ColorStateList.valueOf(Color.WHITE) }
                root.addView(marker, FrameLayout.LayoutParams(c.dp(24), c.dp(24), Gravity.TOP or Gravity.END).apply {
                    setMargins(c.dp(5), c.dp(5), c.dp(5), c.dp(5))
                })
                Holder(root, subtitle = duration, image = photo, marker = marker)
            }
            GalleryRow.COMMON -> {
                val root = LinearLayout(c).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(c.dp(7), c.dp(7), c.dp(7), c.dp(7))
                    clickableSurface(c.tone(MaterialR.attr.colorSurfaceContainerLowest), 20)
                }
                margin(root, 70, 5, 6)
                val photo = image().apply {
                    background = c.shape(c.tone(MaterialR.attr.colorSurfaceContainer), 14)
                    clipToOutline = true
                }
                root.addView(photo, LinearLayout.LayoutParams(c.dp(56), -1))
                val texts = LinearLayout(c).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(c.dp(10), 0, 0, 0)
                }
                val title = c.label("", 15f).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
                val count = c.label("", 12f, true).apply { setPadding(0, c.dp(5), 0, 0) }
                texts.addView(title); texts.addView(count)
                root.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
                Holder(root, title, count, photo)
            }
            GalleryRow.ALBUM, GalleryRow.PERSON -> {
                val root = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
                margin(root, -2, 6, 6)
                val frame = SquareFrame(c).apply {
                    background = c.shape(c.tone(MaterialR.attr.colorSurfaceContainerHigh), 22)
                    clipToOutline = true
                }
                val photo = image()
                frame.addView(photo, FrameLayout.LayoutParams(-1, -1))
                val symbol = image().apply { scaleType = ImageView.ScaleType.CENTER_INSIDE }
                frame.addView(symbol, FrameLayout.LayoutParams(c.dp(42), c.dp(42), Gravity.CENTER))
                val title = c.label("", 15f).apply {
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                    setPadding(c.dp(6), c.dp(9), c.dp(4), c.dp(4))
                }
                val count = c.label("", 12f, true).apply { setPadding(c.dp(6), 0, 0, c.dp(5)) }
                root.addView(frame); root.addView(title); root.addView(count)
                Holder(root, title, count, photo, symbol = symbol)
            }
            GalleryRow.EMPTY -> {
                val root = LinearLayout(c).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                    setPadding(c.dp(24), c.dp(20), c.dp(24), c.dp(20))
                }
                margin(root, -2)
                val icon = image().apply {
                    setImageResource(R.drawable.ic_image)
                    imageTintList = ColorStateList.valueOf(c.tone(MaterialR.attr.colorOnSurfaceVariant))
                }
                root.addView(icon, LinearLayout.LayoutParams(c.dp(42), c.dp(42)))
                val title = c.label("", 18f).apply {
                    gravity = Gravity.CENTER; setPadding(0, c.dp(18), 0, c.dp(8))
                }
                val detail = c.label("", 14f, true).apply { gravity = Gravity.CENTER; minHeight = c.dp(48) }
                root.addView(title); root.addView(detail)
                Holder(root, title, detail)
            }
            else -> {
                val title = c.label("", when (viewType) {
                    GalleryRow.TITLE -> 34f
                    GalleryRow.SECTION -> 19f
                    else -> 13f
                }, viewType == GalleryRow.FOOTER)
                title.setPadding(c.dp(25), 0, c.dp(25), 0)
                if (viewType == GalleryRow.TITLE) title.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                margin(title, when (viewType) {
                    GalleryRow.SPACE -> 76
                    GalleryRow.TITLE -> 72
                    GalleryRow.SECTION -> 52
                    else -> 70
                })
                Holder(title, title)
            }
        }
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position)
        val c = holder.root.context
        if (row.kind == GalleryRow.SPACE) holder.root.layoutParams.height = c.dp(
            if (c.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) 52
            else if (row.id == "photoSpace") 64 else 76)
        holder.title?.text = row.title
        holder.subtitle?.text = row.subtitle
        if (row.kind == GalleryRow.COMMON) holder.subtitle?.apply {
            val glyph = c.getDrawable(row.icon)?.mutate()?.apply {
                setTint(c.tone(MaterialR.attr.colorOnSurfaceVariant))
                setBounds(0, 0, c.dp(13), c.dp(13))
            }
            compoundDrawablePadding = c.dp(3)
            setCompoundDrawablesRelative(glyph, null, null, null)
        }
        holder.image?.let { image ->
            if (row.cover == null) {
                requests.clear(image)
                image.setImageDrawable(null)
                if (row.kind == GalleryRow.COMMON) {
                    image.setImageResource(row.icon)
                    image.scaleType = ImageView.ScaleType.CENTER_INSIDE
                    image.imageTintList = ColorStateList.valueOf(c.tone(MaterialR.attr.colorOnSurfaceVariant))
                }
            } else {
                image.imageTintList = null
                image.scaleType = ImageView.ScaleType.CENTER_CROP
                // Animated images stay still in the grid; playback belongs in the viewer.
                requests.asBitmap().load(row.cover.media.uri)
                    .signature(ObjectKey(row.cover.media.dateModified.time))
                    .centerCrop().dontAnimate()
                    .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                    .placeholder(R.drawable.thumbnail_placeholder)
                    .error(R.drawable.ic_no_photography).into(image)
            }
            image.alpha = if (row.selected) .65f else 1f
        }
        holder.symbol?.apply {
            isVisible = row.cover == null
            setImageResource(row.icon)
            imageTintList = ColorStateList.valueOf(c.tone(androidx.appcompat.R.attr.colorPrimary))
            (parent as View).background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(c.tone(MaterialR.attr.colorSurfaceContainerHigh),
                    c.tone(MaterialR.attr.colorSecondaryContainer))).apply {
                cornerRadius = c.dp(22).toFloat()
            }
        }
        holder.marker?.apply {
            isVisible = row.selecting || row.cover?.media?.isFavorite == true
            setImageResource(if (row.selecting) {
                if (row.selected) R.drawable.ic_check_circle else R.drawable.ic_check_circle_outline
            } else R.drawable.ic_star)
            imageTintList = ColorStateList.valueOf(if (row.selected)
                c.tone(MaterialR.attr.colorPrimaryContainer) else Color.WHITE)
        }
        if (row.kind == GalleryRow.PHOTO) {
            holder.subtitle?.isVisible = row.cover?.isVideo == true
        }
        if (row.kind == GalleryRow.SECTION) {
            holder.title?.apply {
                setPadding(c.dp(if (row.id.startsWith("day:")) 25 else 10), 0, c.dp(25), 0)
                setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0,
                    if (row.action.isNotEmpty()) R.drawable.ic_gallery_chevron else 0, 0)
                compoundDrawableTintList = ColorStateList.valueOf(c.tone(MaterialR.attr.colorOnSurfaceVariant))
                isAccessibilityHeading = true
            }
        }
        holder.root.contentDescription = when (row.kind) {
            GalleryRow.PHOTO -> "${row.cover?.media?.displayName}, ${row.cover?.day}" +
                if (row.subtitle.isNotEmpty()) ", ${row.subtitle}" else ""
            GalleryRow.COMMON, GalleryRow.ALBUM, GalleryRow.PERSON -> "${row.title}, ${row.subtitle}"
            else -> null
        }
        holder.root.isSelected = row.selected
        holder.root.setOnClickListener { click(row) }
        holder.root.setOnLongClickListener { hold(row); true }
        val interactive = row.action.isNotEmpty() || row.kind == GalleryRow.PHOTO
        holder.root.isClickable = interactive
        holder.root.isLongClickable = row.kind in setOf(GalleryRow.PHOTO, GalleryRow.ALBUM, GalleryRow.PERSON)
        holder.root.isFocusable = interactive
    }

    override fun onViewRecycled(holder: Holder) {
        holder.image?.let { requests.clear(it) }
        super.onViewRecycled(holder)
    }
}
