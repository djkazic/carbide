package com.carbide.wallet.data

import android.content.Context
import com.carbide.wallet.data.model.Contact
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContactStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val file: File get() = File(context.filesDir, "contacts.json")
    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()

    init {
        _contacts.value = load()
    }

    fun add(contact: Contact) {
        val list = _contacts.value.toMutableList()
        // Don't add duplicates by address
        if (list.none { it.lnAddress == contact.lnAddress }) {
            list.add(contact)
            list.sortBy { it.name.lowercase() }
            _contacts.value = list
            persist(list)
        }
    }

    fun remove(contact: Contact) {
        val list = _contacts.value.filter { it.lnAddress != contact.lnAddress }
        _contacts.value = list
        persist(list)
    }

    private fun load(): List<Contact> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                Contact(
                    name = obj.getString("name"),
                    lnAddress = obj.getString("lnAddress"),
                )
            }.sortedBy { it.name.lowercase() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun persist(contacts: List<Contact>) {
        val arr = JSONArray()
        contacts.forEach { c ->
            arr.put(JSONObject().apply {
                put("name", c.name)
                put("lnAddress", c.lnAddress)
            })
        }
        file.writeText(arr.toString())
    }
}
