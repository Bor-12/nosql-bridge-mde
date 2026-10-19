import type { AstNode, LangiumCoreServices, LangiumDocument } from 'langium';
import * as path from 'node:path';
import * as fs from 'node:fs';
import { URI } from 'langium';

export async function extractDocument(fileName: string, services: LangiumCoreServices): Promise<LangiumDocument> {
    const extensions = services.LanguageMetaData.fileExtensions;
    if (!extensions.includes(path.extname(fileName))) {
        throw new Error(`Please choose a file with one of these extensions: ${extensions}.`);
    }

    if (!fs.existsSync(fileName)) {
        throw new Error(`File ${fileName} does not exist.`);
    }

    const document = await services.shared.workspace.LangiumDocuments.getOrCreateDocument(URI.file(path.resolve(fileName)));
    await services.shared.workspace.DocumentBuilder.build([document], { validation: true });

    const validationErrors = (document.diagnostics ?? []).filter(e => e.severity === 1);
    if (validationErrors.length > 0) {
        const diagnostics = validationErrors.map(validationError =>
            `line ${validationError.range.start.line + 1}: ${validationError.message} [${document.textDocument.getText(validationError.range)}]`
        );
        throw new Error(['There are validation errors:', ...diagnostics].join('\n'));
    }

    return document;
}

export async function extractAstNode<T extends AstNode>(fileName: string, services: LangiumCoreServices): Promise<T> {
    return (await extractDocument(fileName, services)).parseResult?.value as T;
}

interface FilePathData {
    destination: string,
    name: string
}

export function extractDestinationAndName(destination: string): FilePathData {
    return {
        destination: path.dirname(destination),
        name: path.basename(destination)
    };
}
