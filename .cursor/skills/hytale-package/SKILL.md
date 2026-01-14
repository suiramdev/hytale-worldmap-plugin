---
name: hytale-package
description: Guide for working with Hytale Server API classes and methods from decompiled sources
---

# Hytale Server API Reference

This skill provides guidance for working with the Hytale Server API. The Hytale Server API classes and methods are available in decompiled form for reference.

**Note:** This is an **API** (Application Programming Interface), not an SDK. The server JAR provides the API interfaces and classes that plugins use to interact with the Hytale server at runtime.

## IMPORTANT: Search in decompiled/com/hypixel/hytale Package

**When working with Hytale API for any purpose, you MUST search in the `decompiled/com/hypixel/hytale/` package first.** This is the primary source for:
- Finding Hytale Server API classes and interfaces
- Understanding method signatures and parameters
- Discovering available API functionality
- Learning how to use Hytale's plugin system

The decompiled sources are also available as a Gradle package dependency (configured in `build.gradle.kts`), but the `decompiled/com/hypixel/hytale/` package provides the most accessible and searchable reference.

## Decompiled Sources Location

**Always search in the `decompiled/com/hypixel/hytale/` package when looking for anything related to using the Hytale API.**

The decompiled Hytale Server API contains:
- All Hytale server classes and interfaces
- API methods and their signatures
- Event handlers and listeners
- Plugin system interfaces (e.g., `JavaPlugin`, `PluginBase`)
- Game mechanics and utilities

## Usage Guidelines

1. **Class Discovery**: When you need to find a Hytale Server API class, search in `decompiled/com/hypixel/hytale/` first
2. **Method Signatures**: Reference decompiled sources in `decompiled/com/hypixel/hytale/` to understand method parameters and return types
3. **API Patterns**: Study decompiled code in `decompiled/com/hypixel/hytale/` to understand Hytale's API patterns and conventions
4. **Compile-Time Reference**: The actual JAR (`libs/hytale-server.jar`) is used at compile time as `compileOnly` dependency (via Gradle package in `build.gradle.kts`), but decompiled sources in `decompiled/com/hypixel/hytale/` provide readable reference

## Search Strategy

**For any Hytale API usage, search in the `decompiled/com/hypixel/hytale/` package:**
- Use semantic search in `decompiled/com/hypixel/hytale/` package when looking for Hytale Server API functionality
- Search `decompiled/com/hypixel/hytale/` to find classes, methods, events, and interfaces you need
- The Hytale API classes are located in the `com.hypixel.hytale.*` package structure within `decompiled/com/hypixel/hytale/`
- Check decompiled sources in `decompiled/com/hypixel/hytale/` before implementing plugin features that interact with Hytale APIs
- The Gradle package (configured in `build.gradle.kts`) provides compile-time access, but `decompiled/com/hypixel/hytale/` is the best source for discovery and reference
