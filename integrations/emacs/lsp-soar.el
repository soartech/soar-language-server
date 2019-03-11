;; Work in progress.
;;
;; This depends on the lsp-mode package, and a pre-existing soar-mode.

(lsp-register-client
 (make-lsp-client :new-connection (lsp-stdio-connection "soar-language-server")
                  :major-modes '(soar-mode)
                  :priority -1
                  :server-id 'soar-ls))
