package org.monogram.presentation.settings.privacy

import org.monogram.domain.models.PrivacyRule
import org.monogram.domain.models.PrivacyValue
import org.monogram.domain.repository.PrivacyKey

internal data class PrivacyRuleConfig(
    val baseValue: PrivacyValue,
    val allowUsers: List<Long> = emptyList(),
    val disallowUsers: List<Long> = emptyList(),
    val allowChats: List<Long> = emptyList(),
    val disallowChats: List<Long> = emptyList()
)

internal fun List<PrivacyRule>.toPrivacyValue(): PrivacyValue {
    return when {
        any { it is PrivacyRule.AllowAll } -> PrivacyValue.EVERYBODY
        any { it is PrivacyRule.AllowContacts } -> PrivacyValue.MY_CONTACTS
        any { it is PrivacyRule.AllowNone } -> PrivacyValue.NOBODY
        any { it is PrivacyRule.DisallowContacts || it is PrivacyRule.DisallowUsers || it is PrivacyRule.DisallowChatMembers } -> PrivacyValue.EVERYBODY
        any { it is PrivacyRule.AllowUsers || it is PrivacyRule.AllowChatMembers } -> PrivacyValue.NOBODY
        else -> PrivacyValue.EVERYBODY
    }
}

internal fun List<PrivacyRule>.toPrivacyRuleConfig(): PrivacyRuleConfig {
    val allowUsers = mutableListOf<Long>()
    val disallowUsers = mutableListOf<Long>()
    val allowChats = mutableListOf<Long>()
    val disallowChats = mutableListOf<Long>()

    forEach { rule ->
        when (rule) {
            is PrivacyRule.AllowUsers -> allowUsers.addAll(rule.userIds)
            is PrivacyRule.DisallowUsers -> disallowUsers.addAll(rule.userIds)
            is PrivacyRule.AllowChatMembers -> allowChats.addAll(rule.chatIds)
            is PrivacyRule.DisallowChatMembers -> disallowChats.addAll(rule.chatIds)
            else -> Unit
        }
    }

    return PrivacyRuleConfig(
        baseValue = toPrivacyValue(),
        allowUsers = allowUsers,
        disallowUsers = disallowUsers,
        allowChats = allowChats,
        disallowChats = disallowChats
    )
}

internal fun buildPrivacyRules(
    key: PrivacyKey,
    value: PrivacyValue,
    allowUsers: List<Long>,
    disallowUsers: List<Long>,
    allowChats: List<Long>,
    disallowChats: List<Long>
): List<PrivacyRule> {
    return buildList {
        if (allowUsers.isNotEmpty()) add(PrivacyRule.AllowUsers(allowUsers))
        if (disallowUsers.isNotEmpty()) add(PrivacyRule.DisallowUsers(disallowUsers))
        if (allowChats.isNotEmpty()) add(PrivacyRule.AllowChatMembers(allowChats))
        if (disallowChats.isNotEmpty()) add(PrivacyRule.DisallowChatMembers(disallowChats))

        when (value) {
            PrivacyValue.EVERYBODY -> add(PrivacyRule.AllowAll)
            PrivacyValue.MY_CONTACTS -> {
                add(PrivacyRule.AllowContacts)
                if (key != PrivacyKey.PHONE_NUMBER_SEARCH) {
                    add(PrivacyRule.AllowNone)
                }
            }

            PrivacyValue.NOBODY -> {
                if (key == PrivacyKey.PHONE_NUMBER_SEARCH) {
                    add(PrivacyRule.AllowContacts)
                } else {
                    add(PrivacyRule.AllowNone)
                }
            }
        }
    }
}
