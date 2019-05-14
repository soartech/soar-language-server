DIAGRAMS += diagrams/uml/analysis-scheduling.png
DIAGRAMS += diagrams/uml/data-structures.png

help: ## Prints a help guide
	@echo "Available tasks:"
	@grep -E '^[\%a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-30s\033[0m %s\n", $$1, $$2}'

watch: ## Automatically build design docs and slides whenever files change
	watchexec -i '*pdf*' make design slides

design: diagrams ## Build documentation
	pandoc -o design.pdf design.md -V geometry:margin=1in -V papersize:a5 # -f markdown-implicit_figures

diagrams: $(DIAGRAMS) ## Build diagrams

diagrams/dia/%.png: diagrams/dia/%.dia
	dia -e $@ $<

diagrams/dot/%.png: diagrams/dot/%.dot
	dot -Tpng $< > $@

diagrams/uml/%.png: diagrams/uml/%.plantuml
	plantuml $< -Sshadowing=false

clean: ## Remove generated artifacts
	rm -f *.pdf diagrams/**/*png
