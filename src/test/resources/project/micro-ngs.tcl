# This provides a tiny slice of the NGS API.

set NGS_YES *YES*
set NGS_NO  *NO*

proc ngs-match-top-state { id } {
    return "(state $id ^superstate nil)"
}

proc ngs-create-attribute { id attr value } {
    return "($id ^$attr $value)"
}

proc ngs-bind { id angs } {
    return ""
}
