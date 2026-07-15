package com.example.devcontrol.data

class SecurityPolicy {
    fun validateCommand(command: String): String {
        val normalized = command.trim()
        require(normalized.isNotEmpty()) { "O comando não pode estar vazio" }
        require('\u0000' !in normalized) { "O comando contém caractere inválido" }
        require(normalized.length <= MAX_COMMAND_LENGTH) { "O comando excede o limite permitido" }
        return normalized
    }

    fun safeFileName(name: String): String {
        val safe = name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(120)
        return safe.ifBlank { "document.txt" }
    }

    private companion object {
        const val MAX_COMMAND_LENGTH = 32_768
    }
}
