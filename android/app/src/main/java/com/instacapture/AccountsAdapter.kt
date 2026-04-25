package com.instacapture

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

/**
 * AccountsAdapter — отображение списка захваченных аккаунтов в RecyclerView.
 * Показывает только безопасные поля (username, email, phone, дата).
 * Пароль НЕ отображается в UI приложения.
 */
class AccountsAdapter(
    private var items: List<AccountListItem> = emptyList()
) : RecyclerView.Adapter<AccountsAdapter.ViewHolder>() {

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val card: MaterialCardView = itemView.findViewById(R.id.cardAccount)
        val tvUsername: TextView = itemView.findViewById(R.id.tvUsername)
        val tvEmail: TextView = itemView.findViewById(R.id.tvEmail)
        val tvPhone: TextView = itemView.findViewById(R.id.tvPhone)
        val tvCapturedAt: TextView = itemView.findViewById(R.id.tvCapturedAt)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_account, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvUsername.text = "Username: ${item.username ?: "—"}"
        holder.tvEmail.text = "Email: ${item.email ?: "—"}"
        holder.tvPhone.text = "Phone: ${item.phone ?: "—"}"
        holder.tvCapturedAt.text = "Захвачен: ${item.capturedAtFormatted}"
    }

    override fun getItemCount(): Int = items.size

    fun updateData(newItems: List<AccountListItem>) {
        items = newItems
        notifyDataSetChanged()
    }
}