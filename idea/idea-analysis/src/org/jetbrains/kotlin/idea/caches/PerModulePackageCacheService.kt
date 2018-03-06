/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.idea.caches

import com.intellij.ProjectTopics
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.rootManager
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.*
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.PsiTreeChangeEventImpl
import com.intellij.psi.impl.PsiTreeChangePreprocessor
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.containers.ContainerUtil
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.idea.caches.PerModulePackageCacheService.Companion.FULL_DROP_THRESHOLD
import org.jetbrains.kotlin.idea.caches.project.ModuleSourceInfo
import org.jetbrains.kotlin.idea.caches.project.getModuleInfoByVirtualFile
import org.jetbrains.kotlin.idea.caches.project.getNullableModuleInfo
import org.jetbrains.kotlin.idea.stubindex.PackageIndexUtil
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPackageDirective
import org.jetbrains.kotlin.psi.psiUtil.getChildrenOfType
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

class KotlinPackageContentModificationListener(private val project: Project) {
    init {
        val connection = project.messageBus.connect()

        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun before(events: MutableList<out VFileEvent>) = onEvents(events)
            override fun after(events: List<VFileEvent>) = onEvents(events)

            private fun isRelevant(it: VFileEvent): Boolean =
                it is VFileMoveEvent || it is VFileCreateEvent || it is VFileCopyEvent || it is VFileDeleteEvent

            fun onEvents(events: List<VFileEvent>) {

                val service = PerModulePackageCacheService.getInstance(project)
                if (events.size >= FULL_DROP_THRESHOLD) {
                    service.onTooComplexChange()
                } else {
                    events
                        .asSequence()
                        .filter { it.file != null }
                        .filter(::isRelevant)
                        .filter {
                            val vFile = it.file!!
                            vFile.isDirectory || FileTypeRegistry.getInstance().getFileTypeByFileName(vFile.name) == KotlinFileType.INSTANCE
                        }
                        .forEach { event -> service.notifyPackageChange(event) }
                }
            }
        })

        connection.subscribe(ProjectTopics.PROJECT_ROOTS, object : ModuleRootListener {
            override fun rootsChanged(event: ModuleRootEvent?) {
                PerModulePackageCacheService.getInstance(project).onTooComplexChange()
            }
        })
    }
}

class KotlinPackageStatementPsiTreeChangePreprocessor(private val project: Project) : PsiTreeChangePreprocessor {
    override fun treeChanged(event: PsiTreeChangeEventImpl) {
        val file = event.file as? KtFile ?: return

        when (event.code) {
            PsiTreeChangeEventImpl.PsiEventType.CHILD_ADDED,
            PsiTreeChangeEventImpl.PsiEventType.CHILD_MOVED,
            PsiTreeChangeEventImpl.PsiEventType.CHILD_REPLACED,
            PsiTreeChangeEventImpl.PsiEventType.CHILD_REMOVED -> {
                val child = event.child ?: return
                if (child.getParentOfType<KtPackageDirective>(false) != null)
                    ServiceManager.getService(project, PerModulePackageCacheService::class.java).notifyPackageChange(file)
            }
            PsiTreeChangeEventImpl.PsiEventType.CHILDREN_CHANGED -> {
                val parent = event.parent ?: return
                if (parent.getChildrenOfType<KtPackageDirective>().any())
                    ServiceManager.getService(project, PerModulePackageCacheService::class.java).notifyPackageChange(file)
            }
            else -> {
            }
        }
    }
}

private typealias ImplicitPackageData = MutableMap<FqName, MutableList<VirtualFile>>

class PerModulePackageCacheService(private val project: Project) {

    /*
     * Disposal of entries handled by Module child Disposable registered in packageExists
     * Actually an StrongMap<Module, SoftMap<ModuleSourceInfo, SoftMap<FqName, Boolean>>>
     */
    private val cache = ConcurrentHashMap<Module, ConcurrentMap<ModuleSourceInfo, ConcurrentMap<FqName, Boolean>>>()
    private val implicitPackageCache = ConcurrentHashMap<VirtualFile, ImplicitPackageData>()

    private val pendingVFileChanges: MutableSet<VFileEvent> = mutableSetOf()
    private val pendingKtFileChanges: MutableSet<KtFile> = mutableSetOf()

    private val projectScope = GlobalSearchScope.projectScope(project)

    internal fun onTooComplexChange(): Unit = synchronized(this) {
        pendingVFileChanges.clear()
        pendingKtFileChanges.clear()
        cache.clear()
        implicitPackageCache.clear()
    }

    internal fun notifyPackageChange(file: VFileEvent): Unit = synchronized(this) {
        pendingVFileChanges += file
    }

    internal fun notifyPackageChange(file: KtFile): Unit = synchronized(this) {
        pendingKtFileChanges += file
    }

    private fun invalidateCacheForModuleSourceInfo(moduleSourceInfo: ModuleSourceInfo) {
        val perSourceInfoData = cache[moduleSourceInfo.module] ?: return
        val dataForSourceInfo = perSourceInfoData[moduleSourceInfo] ?: return
        dataForSourceInfo.clear()
    }

    private fun checkPendingChanges() = synchronized(this) {
        if (pendingVFileChanges.size + pendingKtFileChanges.size >= FULL_DROP_THRESHOLD) {
            onTooComplexChange()
        } else {

            pendingVFileChanges.forEach { event ->
                val vfile = event.file ?: return@forEach
                // When VirtualFile !isValid (deleted for example), it impossible to use getModuleInfoByVirtualFile
                // For directory we must check both is it in some sourceRoot, and is it contains some sourceRoot
                if (vfile.isDirectory || !vfile.isValid) {
                    for ((module, data) in cache) {
                        val sourceRootUrls = module.rootManager.sourceRootUrls
                        if (sourceRootUrls.any { url ->
                                vfile.containedInOrContains(url)
                            }) {
                            data.clear()
                        }
                    }
                } else {
                    (getModuleInfoByVirtualFile(project, vfile) as? ModuleSourceInfo)?.let {
                        invalidateCacheForModuleSourceInfo(it)
                    }
                }
                updateImplicitPackageCache(event)
            }
            pendingVFileChanges.clear()

            pendingKtFileChanges.forEach { file ->
                if (file.virtualFile != null && file.virtualFile !in projectScope) {
                    return@forEach
                }
                (file.getNullableModuleInfo() as? ModuleSourceInfo)?.let { invalidateCacheForModuleSourceInfo(it) }
            }
            pendingKtFileChanges.clear()
        }
    }

    private fun VirtualFile.containedInOrContains(root: String) =
        (VfsUtilCore.isEqualOrAncestor(url, root)
                || isDirectory && VfsUtilCore.isEqualOrAncestor(root, url))


    fun packageExists(packageFqName: FqName, moduleInfo: ModuleSourceInfo): Boolean {
        val module = moduleInfo.module
        checkPendingChanges()

        val perSourceInfoCache = cache.getOrPut(module) {
            Disposer.register(module, Disposable { cache.remove(module) })
            ContainerUtil.createConcurrentSoftMap()
        }
        val cacheForCurrentModuleInfo = perSourceInfoCache.getOrPut(moduleInfo) {
            ContainerUtil.createConcurrentSoftMap()
        }
        return cacheForCurrentModuleInfo.getOrPut(packageFqName) {
            PackageIndexUtil.packageExists(packageFqName, moduleInfo.contentScope(), project)
        }
    }

    fun getImplicitPackagePrefix(sourceRoot: VirtualFile): FqName {
        val implicitPackageMap = implicitPackageCache.getOrPut(sourceRoot) { analyzeImplicitPackagePrefixes(sourceRoot) }
        return implicitPackageMap.keys.singleOrNull() ?: FqName.ROOT
    }

    private fun analyzeImplicitPackagePrefixes(sourceRoot: VirtualFile): MutableMap<FqName, MutableList<VirtualFile>> {
        val result = mutableMapOf<FqName, MutableList<VirtualFile>>()
        val ktFiles = sourceRoot.children.filter { it.fileType == KotlinFileType.INSTANCE }
        for (ktFile in ktFiles) {
            val psiFile = PsiManager.getInstance(project).findFile(ktFile) as? KtFile ?: continue
            result.getOrPut(psiFile.packageFqName) { mutableListOf() }.add(ktFile)
        }
        return result
    }

    private fun updateImplicitPackageCache(event: VFileEvent) {
    }

    companion object {
        const val FULL_DROP_THRESHOLD = 1000

        fun getInstance(project: Project): PerModulePackageCacheService =
            ServiceManager.getService(project, PerModulePackageCacheService::class.java)
    }
}