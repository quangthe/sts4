/*******************************************************************************
 * Copyright (c) 2016-2017 Pivotal, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Pivotal, Inc. - initial API and implementation
 *******************************************************************************/
package org.springframework.ide.vscode.commons.languageserver.completion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentPositionParams;
import org.eclipse.lsp4j.TextEdit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ide.vscode.commons.languageserver.completion.DocumentEdits.TextReplace;
import org.springframework.ide.vscode.commons.languageserver.util.SimpleLanguageServer;
import org.springframework.ide.vscode.commons.languageserver.util.SimpleTextDocumentService;
import org.springframework.ide.vscode.commons.languageserver.util.SortKeys;
import org.springframework.ide.vscode.commons.util.BadLocationException;
import org.springframework.ide.vscode.commons.util.Log;
import org.springframework.ide.vscode.commons.util.Renderable;
import org.springframework.ide.vscode.commons.util.StringUtil;
import org.springframework.ide.vscode.commons.util.text.TextDocument;

import com.google.common.collect.ImmutableList;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Adapts a {@link ICompletionEngine}, wrapping it, to implement {@link VscodeCompletionEngine}
 */
public class VscodeCompletionEngineAdapter implements VscodeCompletionEngine {

	public static class LazyCompletionResolver {
		private int nextId = 0; //Used to assign unique id to completion items.

		private Map<String, Consumer<CompletionItem>> resolvers = new HashMap<>();

		private String nextId() {
			//Warning: it's tempting to return 'int' and use a Integer object as id but...
			// Looks like that breaks things because the Integer becomes a Double after being
			// serialized and deserialized to json.
			return ""+(nextId++);
		}

		public synchronized String resolveLater(ICompletionProposal completion, TextDocument doc) {
			String id = nextId();
			resolvers.put(id, (unresolved) -> {
				try {
					resolveItem(doc, completion, unresolved);
				} catch (Exception e) {
					Log.log(e);
				}
			});
			return id;
		}

		public synchronized void resolveNow(CompletionItem unresolved) {
			Object id = unresolved.getData();
			if (id!=null) {
				Consumer<CompletionItem> resolver = resolvers.get(id);
				if (resolver!=null) {
					resolver.accept(unresolved);
					unresolved.setData(null); //No longer needed after item is resolved.
				} else {
					Log.warn("Couldn't resolve completion item. Did it already get flushed from the resolver's cache? "+unresolved.getLabel());
				}
			}
		}

		public synchronized void clear() {
			resolvers.clear();
		}
	}

	private final static int DEFAULT_MAX_COMPLETIONS = 50;
	private int maxCompletions = DEFAULT_MAX_COMPLETIONS; //TODO: move this to CompletionEngineOptions.
	final static Logger logger = LoggerFactory.getLogger(VscodeCompletionEngineAdapter.class);

	private SimpleLanguageServer server;
	private ICompletionEngine engine;
	private final LazyCompletionResolver resolver;

	/**
	 * By setting a non-null {@link LazyCompletionResolver} you can enable lazy completion resolution.
	 * By default lazy resolution is not implemented.
	 * <p>
	 * The resolver is injected rather than created locally to allow sharing it between multiple
	 * engines.
	 */
	public VscodeCompletionEngineAdapter(SimpleLanguageServer server, ICompletionEngine engine, LazyCompletionResolver resolver) {
		this.server = server;
		this.engine = engine;
		this.resolver = resolver;
	}

	public void setMaxCompletions(int maxCompletions) {
		this.maxCompletions = maxCompletions;
	}

	@Override
	public CompletableFuture<CompletionList> getCompletions(TextDocumentPositionParams params) {
		return getCompletionsMono(params).toFuture();
	}

	private Mono<CompletionList> getCompletionsMono(TextDocumentPositionParams params) {
		SimpleTextDocumentService documents = server.getTextDocumentService();
		if (documents.get(params) != null) {
			TextDocument doc = documents.get(params).copy();
			return Mono.fromCallable(() -> {
				if (resolver!=null) {
					//Assumes we don't have more than one completion request in flight from the client.
					// So when a new request arrives we can forget about the old unresolved items:
					resolver.clear();
				}
				//TODO: This callable is a 'big lump of work' so can't be canceled in pieces.
				// Should we push using of reactive streams down further and compose this all
				// using reactive style? If not then this is overkill could just as well use
				// only standard Java API such as Executor and CompletableFuture directly.
				int offset = doc.toOffset(params.getPosition());
				List<ICompletionProposal> completions = new ArrayList<>(engine.getCompletions(doc, offset));
				Collections.sort(completions, ScoreableProposal.COMPARATOR);
				CompletionList list = new CompletionList();
				list.setIsIncomplete(false);
				List<CompletionItem> items = new ArrayList<>(completions.size());
				SortKeys sortkeys = new SortKeys();
				int count = 0;
				for (ICompletionProposal c : completions) {
					count++;
					if (maxCompletions > 0 && count>maxCompletions) {
						list.setIsIncomplete(true);
						break;
					}
					try {
						items.add(adaptItem(doc, c, sortkeys));
					} catch (Exception e) {
						logger.error("error computing completion", e);
					}
				}
				list.setItems(items);
				return list;
			})
			.subscribeOn(Schedulers.elastic()); //!!! without this the mono will just be computed on the same thread that calls it.
		}
		return Mono.just(SimpleTextDocumentService.NO_COMPLETIONS);
	}

	private CompletionItem adaptItem(TextDocument doc, ICompletionProposal completion, SortKeys sortkeys) throws Exception {
		CompletionItem item = new CompletionItem();
		item.setLabel(completion.getLabel());
		item.setKind(completion.getKind());
		item.setSortText(sortkeys.next());
		item.setFilterText(completion.getFilterText());
		item.setDetail(completion.getDetail());
		resolveEdits(doc, completion, item); //Warning. Its not allowed by LSP spec to resolveEdits
											//lazy as we used to do in the past.
		if (resolver!=null) {
			item.setData(resolver.resolveLater(completion, doc));
		} else {
			resolveItem(doc, completion, item);
		}
		return item;
	}

	private static void resolveItem(TextDocument doc, ICompletionProposal completion, CompletionItem item) throws Exception {
		item.setDocumentation(toMarkdown(completion.getDocumentation()));
		resolveEdits(doc, completion, item);
	}

	private static void resolveEdits(TextDocument doc, ICompletionProposal completion, CompletionItem item) {
		Optional<TextEdit> mainEdit = adaptEdits(doc, completion.getTextEdit());
		if (mainEdit.isPresent()) {
			item.setTextEdit(mainEdit.get());
			item.setInsertTextFormat(InsertTextFormat.Snippet);
		} else {
			item.setInsertText("");
		}

		completion.getAdditionalEdit().ifPresent(edit -> {
			adaptEdits(doc, edit).ifPresent(extraEdit -> {
				item.setAdditionalTextEdits(ImmutableList.of(extraEdit));
			});
		});
	}

	private static String toMarkdown(Renderable r) {
		if (r!=null) {
			return r.toMarkdown();
		}
		return null;
	}

	private static Optional<TextEdit> adaptEdits(TextDocument doc, DocumentEdits edits) {
		try {
			TextReplace replaceEdit = edits.asReplacement(doc);
			if (replaceEdit==null) {
				//The original edit does nothing.
				return Optional.empty();
			} else {
				TextDocument newDoc = doc.copy();
				edits.apply(newDoc);
				TextEdit vscodeEdit = new TextEdit();
				vscodeEdit.setRange(doc.toRange(replaceEdit.start, replaceEdit.end-replaceEdit.start));
				if (Boolean.getBoolean("lsp.completions.indentation.enable")) {
					vscodeEdit.setNewText(replaceEdit.newText);
				} else {
					vscodeEdit.setNewText(vscodeIndentFix(doc, vscodeEdit.getRange().getStart(), replaceEdit.newText));
				}
				//TODO: cursor offset within newText? for now we assume its always at the end.
				return Optional.of(vscodeEdit);
			}
		}  catch (Exception e) {
			Log.log(e);
			return Optional.empty();
		}
	}

	private static String vscodeIndentFix(TextDocument doc, Position start, String newText) {
		//Vscode applies some magic indent to a multi-line edit text. We do everything ourself so we have adjust for the magic
		// and do some kind of 'inverse magic' here.
		//See here: https://github.com/Microsoft/language-server-protocol/issues/83
		IndentUtil indenter = new IndentUtil(doc);
		try {
			String refIndent = indenter.getReferenceIndent(doc.toOffset(start), doc);
			if (!refIndent.isEmpty()) {
				return  StringUtil.stripIndentation(refIndent, newText);
			}
		} catch (BadLocationException e) {
			Log.log(e);
		}
		return newText;
	}

	@Override
	public CompletableFuture<CompletionItem> resolveCompletion(CompletionItem unresolved) {
		resolver.resolveNow(unresolved);
		return CompletableFuture.completedFuture(unresolved);
	}
}
