const supportedTransformations = new Set<string>([
    'toString',
    'toInteger',
    'toDecimal'
]);

export function isSupportedTransformation(name: string): boolean {
    return supportedTransformations.has(name);
}
