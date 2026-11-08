package dev.mango.app

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.SymbolLookup
import java.lang.invoke.MethodHandle

private val user32 by lazy { SymbolLookup.libraryLookup("user32", Arena.global()) }

fun user32Function(name: String, descriptor: FunctionDescriptor): MethodHandle =
    Linker.nativeLinker().downcallHandle(user32.find(name).get(), descriptor)
