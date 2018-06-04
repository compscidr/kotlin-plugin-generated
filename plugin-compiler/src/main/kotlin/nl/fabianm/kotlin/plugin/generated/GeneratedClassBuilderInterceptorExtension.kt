/*
 * Copyright 2018 Fabian Mastenbroek.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nl.fabianm.kotlin.plugin.generated

import jdk.internal.org.objectweb.asm.Type
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.codegen.ClassBuilder
import org.jetbrains.kotlin.codegen.ClassBuilderFactory
import org.jetbrains.kotlin.codegen.DelegatingClassBuilder
import org.jetbrains.kotlin.codegen.extensions.ClassBuilderInterceptorExtension
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.diagnostics.DiagnosticSink
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.calls.tower.isSynthesized
import org.jetbrains.kotlin.resolve.jvm.diagnostics.JvmDeclarationOrigin
import org.jetbrains.kotlin.resolve.jvm.diagnostics.JvmDeclarationOriginKind
import org.jetbrains.org.objectweb.asm.MethodVisitor

/**
 * A Kotlin compiler plugin that annotates Kotlin-generated methods with `lombok.Generated` to signify to code analyzers
 * that these methods have been generated by the compiler.
 *
 * @property messageCollector The message collector used to log messages.
 * @property annotation The annotation to mark the methods with.
 * @property visible A flag to indicate whether the annotation should be visible.
 */
class GeneratedClassBuilderInterceptorExtension(
    private val messageCollector: MessageCollector,
    private val annotation: FqName,
    private val visible: Boolean
) : ClassBuilderInterceptorExtension {

    /**
     * The annotation descriptor that is used to annotate the generated methods.
     */
    private val annotationDescriptor = Type.getObjectType(annotation.asString().replace(".", "/")).descriptor

    override fun interceptClassBuilderFactory(
        interceptedFactory: ClassBuilderFactory,
        bindingContext: BindingContext,
        diagnostics: DiagnosticSink
    ): ClassBuilderFactory = GeneratedClassBuilderFactory(interceptedFactory)

    private inner class GeneratedClassBuilderFactory(
        private val delegateFactory: ClassBuilderFactory
    ) : ClassBuilderFactory {

        override fun newClassBuilder(origin: JvmDeclarationOrigin): ClassBuilder =
            GeneratedClassBuilder(delegateFactory.newClassBuilder(origin))

        override fun getClassBuilderMode() = delegateFactory.classBuilderMode

        override fun asText(builder: ClassBuilder?): String? =
            delegateFactory.asText((builder as GeneratedClassBuilder).delegateClassBuilder)

        override fun asBytes(builder: ClassBuilder?): ByteArray? =
            delegateFactory.asBytes((builder as GeneratedClassBuilder).delegateClassBuilder)

        override fun close() = delegateFactory.close()
    }

    private inner class GeneratedClassBuilder(
        internal val delegateClassBuilder: ClassBuilder
    ) : DelegatingClassBuilder() {
        /**
         * The current class we are building
         */
        private var currentClass: KtClass? = null

        override fun getDelegate() = delegateClassBuilder

        override fun defineClass(
            origin: PsiElement?,
            version: Int,
            access: Int,
            name: String,
            signature: String?,
            superName: String,
            interfaces: Array<out String>
        ) {
            if (origin is KtClass) {
                currentClass = origin
            }

            super.defineClass(origin, version, access, name, signature, superName, interfaces)
        }

        override fun newMethod(origin: JvmDeclarationOrigin, access: Int, name: String, desc: String, signature: String?, exceptions: Array<out String>?): MethodVisitor {
            val descriptor = origin.descriptor as? FunctionDescriptor
            val visitor = super.newMethod(origin, access, name, desc, signature, exceptions)

            val mark = heuristicSynthesized(origin) ||
                heuristicProperty(origin)

            if (mark) {
                messageCollector.report(CompilerMessageSeverity.LOGGING, "Generated: Annotating $descriptor")
                visitor.visitAnnotation(annotationDescriptor, visible)
            }

            return visitor
        }

        /**
         * A heuristic that detects default getter and setter functions for properties.
         */
        private fun heuristicProperty(origin: JvmDeclarationOrigin): Boolean =
            origin.element is KtProperty || origin.element is KtParameter

        /**
         * A heuristic that detects synthesized methods on a data class like "equals", "copy" and "hashCode".
         */
        private fun heuristicSynthesized(origin: JvmDeclarationOrigin): Boolean =
            (origin.descriptor as? FunctionDescriptor)?.isSynthesized == true || origin.originKind == JvmDeclarationOriginKind.SYNTHETIC
    }
}
