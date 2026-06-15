package com.steamcontroller.android

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Filter
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.steamcontroller.android.databinding.ActivityMappingBinding
import com.steamcontroller.android.input.ButtonCategory
import com.steamcontroller.android.input.SteamButton
import com.steamcontroller.android.input.XboxTarget

class MappingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMappingBinding
    private val targets = XboxTarget.values()
    private val targetNames = targets.map { it.displayName }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMappingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_reset) {
                Prefs.resetMappings(this)
                binding.mappingContainer.removeAllViews()
                buildSections()
                true
            } else false
        }

        buildSections()
    }

    private fun buildSections() {
        // Group buttons by category and render section header + rows
        val grouped = SteamButton.values().groupBy { it.category }
        for (category in ButtonCategory.values()) {
            val buttons = grouped[category] ?: continue
            addSectionHeader(category.title)
            for (source in buttons) addRow(source)
        }
    }

    private fun addSectionHeader(title: String) {
        val view = layoutInflater.inflate(R.layout.item_mapping_section, binding.mappingContainer, false)
        view.findViewById<TextView>(R.id.tvSectionTitle).text = title
        binding.mappingContainer.addView(view)
    }

    private fun addRow(source: SteamButton) {
        val row = layoutInflater.inflate(R.layout.item_mapping_row, binding.mappingContainer, false)
        row.findViewById<TextView>(R.id.tvSourceBadge).text = source.shortLabel
        row.findViewById<TextView>(R.id.tvSourceName).text = source.displayName

        val dropdown = row.findViewById<MaterialAutoCompleteTextView>(R.id.dropdownTarget)
        dropdown.setAdapter(nonFilteringAdapter(targetNames))
        dropdown.threshold = 0
        val current = Prefs.getMapping(this, source)
        dropdown.setText(current.displayName, false)
        dropdown.setOnItemClickListener { _, _, position, _ ->
            Prefs.setMapping(this, source, targets[position])
        }

        binding.mappingContainer.addView(row)
    }

    private fun nonFilteringAdapter(items: List<String>): ArrayAdapter<String> =
        object : ArrayAdapter<String>(this, android.R.layout.simple_dropdown_item_1line, items) {
            private val noFilter = object : Filter() {
                override fun performFiltering(constraint: CharSequence?): FilterResults =
                    FilterResults().apply { values = items; count = items.size }
                override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                    notifyDataSetChanged()
                }
            }
            override fun getFilter(): Filter = noFilter
        }
}
