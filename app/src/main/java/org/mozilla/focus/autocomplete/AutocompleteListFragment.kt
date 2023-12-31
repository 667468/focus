/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.focus.autocomplete

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.support.design.widget.FloatingActionButton
import android.support.v4.app.Fragment
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.support.v7.widget.helper.ItemTouchHelper
import android.support.v7.widget.helper.ItemTouchHelper.SimpleCallback
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.TextView
import kotlinx.android.synthetic.main.fragment_autocomplete_customdomains.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import mozilla.components.browser.domains.CustomDomains
import org.mozilla.focus.R
import org.mozilla.focus.settings.BaseSettingsFragment
import org.mozilla.focus.telemetry.TelemetryWrapper
import org.mozilla.focus.utils.ViewUtils
import java.util.Collections
import kotlin.coroutines.CoroutineContext

typealias DomainFormatter = (String) -> String
/**
 * Fragment showing settings UI listing all custom autocomplete domains entered by the user.
 */
open class AutocompleteListFragment : Fragment(), CoroutineScope {
    private var job = Job()
    override val coroutineContext: CoroutineContext
        get() = job + Dispatchers.Main

    /**
     * ItemTouchHelper for reordering items in the domain list.
     */
    val itemTouchHelper: ItemTouchHelper = ItemTouchHelper(
            object : SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder
                ): Boolean {
                    val from = viewHolder.adapterPosition
                    val to = target.adapterPosition

                    (recyclerView.adapter as DomainListAdapter).move(from, to)

                    return true
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

                override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                    super.onSelectedChanged(viewHolder, actionState)

                    if (viewHolder is DomainViewHolder) {
                        viewHolder.onSelected()
                    }
                }

                override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                    super.clearView(recyclerView, viewHolder)

                    if (viewHolder is DomainViewHolder) {
                        viewHolder.onCleared()
                    }
                }
            })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    /**
     * In selection mode the user can select and remove items. In non-selection mode the list can
     * be reordered by the user.
     */
    open fun isSelectionMode() = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = inflater.inflate(R.layout.fragment_autocomplete_customdomains, container, false)
        val addCustomUrlFab = root.findViewById<FloatingActionButton>(R.id.addCustomUrlFab)
        addCustomUrlFab.setOnClickListener {
            fragmentManager!!
                    .beginTransaction()
                    .replace(R.id.container, AutocompleteAddFragment())
                    .addToBackStack(null)
                    .commit()
        }
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        domainList.layoutManager = LinearLayoutManager(activity, LinearLayoutManager.VERTICAL, false)
        domainList.adapter = DomainListAdapter()
        domainList.setHasFixedSize(true)

        if (!isSelectionMode()) {
            itemTouchHelper.attachToRecyclerView(domainList)
        }
    }

    override fun onResume() {
        super.onResume()

        if (job.isCancelled) {
            job = Job()
        }

        (activity as BaseSettingsFragment.ActionBarUpdater).apply {
            updateTitle(R.string.preference_autocomplete_subitem_manage_sites)
            updateIcon(R.drawable.ic_back)
        }

        (domainList.adapter as DomainListAdapter).refresh(activity!!) {
            activity?.invalidateOptionsMenu()
        }
    }

    override fun onPause() {
        job.cancel()
        super.onPause()
    }

    override fun onCreateOptionsMenu(menu: Menu?, inflater: MenuInflater?) {
        inflater?.inflate(R.menu.menu_autocomplete_list, menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu?) {
        val removeItem = menu?.findItem(R.id.remove)

        removeItem?.let {
            it.isVisible = isSelectionMode() || domainList.adapter!!.itemCount > 1
            val isEnabled = !isSelectionMode() || (domainList.adapter as DomainListAdapter).selection().isNotEmpty()
            ViewUtils.setMenuItemEnabled(it, isEnabled)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem?): Boolean = when (item?.itemId) {
        R.id.remove -> {
            fragmentManager!!
                    .beginTransaction()
                    .replace(R.id.container, AutocompleteRemoveFragment())
                    .addToBackStack(null)
                    .commit()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    /**
     * Adapter implementation for the list of custom autocomplete domains.
     */
    inner class DomainListAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val domains: MutableList<String> = mutableListOf()
        private val selectedDomains: MutableList<String> = mutableListOf()

        fun refresh(context: Context, body: (() -> Unit)? = null) {
            launch(Main) {
                val updatedDomains = async { CustomDomains.load(context) }.await()

                domains.clear()
                domains.addAll(updatedDomains)

                notifyDataSetChanged()

                body?.invoke()
            }
        }

        override fun getItemViewType(position: Int) = DomainViewHolder.LAYOUT_ID

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
                when (viewType) {
                    DomainViewHolder.LAYOUT_ID ->
                        DomainViewHolder(
                                LayoutInflater.from(parent.context).inflate(viewType, parent, false),
                                { AutocompleteDomainFormatter.format(it) })
                    else -> throw IllegalArgumentException("Unknown view type: $viewType")
                }

        override fun getItemCount(): Int = domains.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is DomainViewHolder) {
                holder.bind(
                        domains[position],
                        isSelectionMode(),
                        selectedDomains,
                        itemTouchHelper,
                        this@AutocompleteListFragment)
            }
        }

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            if (holder is DomainViewHolder) {
                holder.checkBoxView.setOnCheckedChangeListener(null)
            }
        }

        fun selection(): List<String> = selectedDomains

        fun move(from: Int, to: Int) {
            Collections.swap(domains, from, to)
            notifyItemMoved(from, to)

            launch(IO) {
                CustomDomains.save(activity!!.applicationContext, domains)

                TelemetryWrapper.reorderAutocompleteDomainEvent(from, to)
            }
        }
    }

    /**
     * ViewHolder implementation for a domain item in the list.
     */
    private class DomainViewHolder(
        itemView: View,
        val domainFormatter: DomainFormatter? = null
    ) : RecyclerView.ViewHolder(itemView) {
        val domainView: TextView = itemView.findViewById(R.id.domainView)
        val checkBoxView: CheckBox = itemView.findViewById(R.id.checkbox)
        val handleView: View = itemView.findViewById(R.id.handleView)

        companion object {
            val LAYOUT_ID = R.layout.item_custom_domain
        }

        fun bind(
            domain: String,
            isSelectionMode: Boolean,
            selectedDomains: MutableList<String>,
            itemTouchHelper: ItemTouchHelper,
            fragment: AutocompleteListFragment
        ) {
            domainView.text = domainFormatter?.invoke(domain) ?: domain

            checkBoxView.visibility = if (isSelectionMode) View.VISIBLE else View.GONE
            checkBoxView.isChecked = selectedDomains.contains(domain)
            checkBoxView.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
                if (isChecked) {
                    selectedDomains.add(domain)
                } else {
                    selectedDomains.remove(domain)
                }

                fragment.activity?.invalidateOptionsMenu()
            }

            handleView.visibility = if (isSelectionMode) View.GONE else View.VISIBLE
            handleView.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    itemTouchHelper.startDrag(this)
                }
                false
            }

            if (isSelectionMode) {
                itemView.setOnClickListener {
                    checkBoxView.isChecked = !checkBoxView.isChecked
                }
            }
        }

        fun onSelected() {
            itemView.setBackgroundColor(Color.DKGRAY)
        }

        fun onCleared() {
            itemView.setBackgroundColor(0)
        }
    }
}
