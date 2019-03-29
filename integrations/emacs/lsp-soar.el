;;; lsp-soar.el --- Language server support for Soar

;; Version 0.1
;; Author: Anthony Deschamps <anthony.j.deschamps@gmail.com>
;; Package-Requires: ((lsp-mode "6.0"))
;; Keywords: lsp, soar
;; URL: https://github.com/soartech/soar-language-server


;;; Commentary:

;; This package provides language server support for the Soar
;; language. To enable, call (lsp) in soar-mode-hook and
;; tcl-mode-hook.

;;; Code:
(require 'lsp)

(defcustom lsp-soar-executable
  "soar-language-server"
  "Path to the Soar language server executable."
  :type 'file
  :group 'lsp-soar)

(lsp-register-client
 (make-lsp-client
  :new-connection (lsp-stdio-connection (lambda () "soar-language-server"))
  :major-modes '(soar-mode tcl-mode)
  :priority -1
  :server-id 'soar-ls))

(provide 'lsp-soar)
;;; lsp-soar.el ends here
