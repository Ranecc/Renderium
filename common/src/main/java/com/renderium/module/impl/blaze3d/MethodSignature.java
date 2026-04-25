// Renderium - Mixin 基础设施
// 方法签名数据结构 - 用于版本适配和方法匹配

package com.renderium.module.impl.blaze3d;

import java.util.Objects;

/**
 * 方法签名数据结构 📝
 * <p>
 * 用于描述目标类中的方法签名信息，支持版本适配器进行方法匹配和验证。
 * 采用 Java Record 实现，保证不可变性和线程安全。
 *
 * <h2>使用场景：</h2>
 * <pre>
 * ┌─────────────────────────────────────────────┐
 * │  1. 版本适配：检测不同 MC 版本的方法签名变化   │
 * │  2. Mixin 注入：精确定位目标注入点            │
 * │  3. 反射调用：安全地调用 Blaze3D 内部方法      │
 * └─────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>示例用法：</h3>
 * <pre>{@code
 * // 定义一个方法签名
 * MethodSignature signature = new MethodSignature(
 *     "com.mojang.blaze3d.pipeline.RenderTarget",
 *     "blitScreen",
 *     "(Lorg/joml/Matrix4f;)V",
 *     false
 * );
 *
 * // 检查签名是否有效
 * boolean valid = VersionAdapter.isMethodSignatureValid("1.20.4", signature);
 * }</pre>
 *
 * @param className      目标类的全限定名（如 "com.mojang.blaze3d.pipeline.RenderTarget"）
 * @param methodName     目标方法名（如 "blitScreen"）
 * @param methodDesc     方法描述符（JVM 格式，如 "(Lorg/joml/Matrix4f;)V"）
 * @param isStatic       是否为静态方法
 *
 * @see VersionAdapter
 * @author Renderium Team
 * @since 2.0.0
 */
public record MethodSignature(
        /** 目标类的全限定名 */
        String className,

        /** 目标方法名 */
        String methodName,

        /** JVM 方法描述符（参数类型+返回类型） */
        String methodDesc,

        /** 是否为静态方法 */
        boolean isStatic
) {

    /**
     * 创建方法签名实例
     * <p>
     * 验证所有非空字段，确保签名的完整性。
     *
     * @param className  目标类的全限定名，不能为 null 或空
     * @param methodName 目标方法名，不能为 null 或空
     * @param methodDesc JVM 方法描述符，不能为 null 或空
     * @param isStatic  是否为静态方法
     * @throws IllegalArgumentException 如果任何必要字段为 null 或空字符串
     */
    public MethodSignature {
        Objects.requireNonNull(className, "className 不能为 null");
        Objects.requireNonNull(methodName, "methodName 不能为 null");
        Objects.requireNonNull(methodDesc, "methodDesc 不能为 null");

        if (className.isBlank()) {
            throw new IllegalArgumentException("className 不能为空字符串");
        }
        if (methodName.isBlank()) {
            throw new IllegalArgumentException("methodName 不能为空字符串");
        }
        if (methodDesc.isBlank()) {
            throw new IllegalArgumentException("methodDesc 不能为空字符串");
        }
    }

    /**
     * 获取方法的简短标识符
     * <p>
     * 格式为：ClassName#methodName:desc
     * 用于日志输出和调试。
     *
     * @return 简短标识符字符串
     */
    public String getShortIdentifier() {
        int lastDot = className.lastIndexOf('.');
        String simpleName = lastDot >= 0 ? className.substring(lastDot + 1) : className;
        return String.format("%s#%s:%s", simpleName, methodName, methodDesc);
    }

    /**
     * 获取完整的方法标识符
     * <p>
     * 格式为：className.methodName:desc [static/non-static]
     * 用于唯一标识一个方法。
     *
     * @return 完整标识符字符串
     */
    public String getFullIdentifier() {
        return String.format("%s.%s:%s [%s]",
                className,
                methodName,
                methodDesc,
                isStatic ? "static" : "instance"
        );
    }

    /**
     * 解析方法描述符获取返回类型
     * <p>
     * 从 JVM 方法描述符中提取返回类型部分。
     *
     * @return 返回类型的描述符（如 "V"、"I"、"Ljava/lang/String;" 等）
     */
    public String getReturnTypeDescriptor() {
        int closeParen = methodDesc.indexOf(')');
        if (closeParen < 0 || closeParen >= methodDesc.length() - 1) {
            return "V"; // 默认返回 void
        }
        return methodDesc.substring(closeParen + 1);
    }

    /**
     * 解析方法描述符获取参数类型列表
     * <p>
     * 从 JVM 方法描述符中提取所有参数类型。
     *
     * @return 参数类型描述符数组
     */
    public String[] getParameterTypeDescriptors() {
        int openParen = methodDesc.indexOf('(');
        int closeParen = methodDesc.indexOf(')');

        if (openParen < 0 || closeParen <= openParen + 1) {
            return new String[0]; // 无参数
        }

        String paramsStr = methodDesc.substring(openParen + 1, closeParen);
        return parseDescriptors(paramsStr);
    }

    /**
     * 获取参数数量
     *
     * @return 方法的参数个数
     */
    public int getParameterCount() {
        return getParameterTypeDescriptors().length;
    }

    /**
     * 判断是否为 void 返回类型
     *
     * @return 如果返回类型为 void 则返回 true
     */
    public boolean isVoidReturn() {
        return "V".equals(getReturnTypeDescriptor());
    }

    /**
     * 将此签名转换为可用于反射调用的参数类型数组
     * <p>
     * 注意：此方法仅返回 Class 数组，实际使用时需要配合 ClassLoader。
     *
     * @return 参数类型 Class 名称数组（可能包含原始类型名称）
     */
    public String[] getParameterClassNames() {
        String[] descriptors = getParameterTypeDescriptors();
        String[] classNames = new String[descriptors.length];

        for (int i = 0; i < descriptors.length; i++) {
            classNames[i] = descriptorToClassName(descriptors[i]);
        }

        return classNames;
    }

    // ==================== 内部工具方法 ====================

    /**
     * 解析多个连续的类型描述符
     *
     * @param descriptors 类型描述符字符串
     * @return 分解后的单个描述符数组
     */
    private static String[] parseDescriptors(String descriptors) {
        java.util.List<String> result = new java.util.ArrayList<>();
        int index = 0;

        while (index < descriptors.length()) {
            char c = descriptors.charAt(index);

            switch (c) {
                case 'Z', 'B', 'C', 'S', 'I', 'J', 'F', 'D' -> {
                    result.add(String.valueOf(c));
                    index++;
                }
                case 'L' -> {
                    int semicolon = descriptors.indexOf(';', index);
                    if (semicolon > index) {
                        result.add(descriptors.substring(index, semicolon + 1));
                        index = semicolon + 1;
                    } else {
                        index++; // 异常情况，跳过
                    }
                }
                case '[' -> {
                    // 数组类型，继续读取后续字符
                    int start = index;
                    while (index < descriptors.length() && descriptors.charAt(index) == '[') {
                        index++;
                    }
                    // 读取元素类型
                    if (index < descriptors.length()) {
                        char nextChar = descriptors.charAt(index);
                        if (nextChar == 'L') {
                            int semicolon = descriptors.indexOf(';', index);
                            if (semicolon > index) {
                                index = semicolon + 1;
                            } else {
                                index++;
                            }
                        } else if ("ZBCSIJFD".indexOf(nextChar) >= 0) {
                            index++;
                        }
                    }
                    result.add(descriptors.substring(start, index));
                }
                default -> index++; // 未知类型，跳过
            };
        }

        return result.toArray(new String[0]);
    }

    /**
     * 将 JVM 描述符转换为类名
     *
     * @param descriptor JVM 类型描述符
     * @return 对应的类名字符串
     */
    private static String descriptorToClassName(String descriptor) {
        if (descriptor == null || descriptor.isEmpty()) {
            return "void";
        }

        return switch (descriptor.charAt(0)) {
            case 'Z' -> "boolean";
            case 'B' -> "byte";
            case 'C' -> "char";
            case 'S' -> "short";
            case 'I' -> "int";
            case 'J' -> "long";
            case 'F' -> "float";
            case 'D' -> "double";
            case 'V' -> "void";
            case 'L' -> descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
            case '[' -> descriptor.replace('/', '.'); // 数组类型保持原样
            default -> descriptor; // 未知类型
        };
    }
}
