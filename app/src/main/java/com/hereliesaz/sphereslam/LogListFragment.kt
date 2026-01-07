package com.hereliesaz.sphereslam

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

class LogListFragment : Fragment() {

    private val viewModel: LogViewModel by activityViewModels()
    private lateinit var adapter: LogAdapter
    private var showOnlyErrors: Boolean = false

    companion object {
        private const val ARG_SHOW_ERRORS = "show_errors"

        fun newInstance(showErrors: Boolean): LogListFragment {
            val fragment = LogListFragment()
            val args = Bundle()
            args.putBoolean(ARG_SHOW_ERRORS, showErrors)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOnlyErrors = arguments?.getBoolean(ARG_SHOW_ERRORS) ?: false
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val recyclerView = RecyclerView(requireContext())
        recyclerView.layoutManager = LinearLayoutManager(context)
        // Keep scroll at bottom
        val layoutManager = recyclerView.layoutManager as LinearLayoutManager
        layoutManager.stackFromEnd = true

        adapter = LogAdapter()
        recyclerView.adapter = adapter

        return recyclerView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            val flow = if (showOnlyErrors) viewModel.errorLogs else viewModel.allLogs
            flow.collect { logs ->
                adapter.setLogs(logs)
                if (logs.isNotEmpty()) {
                    (view as RecyclerView).scrollToPosition(logs.size - 1)
                }
            }
        }
    }
}
