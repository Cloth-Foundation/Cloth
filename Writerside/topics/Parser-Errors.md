# Parser Errors

> Due to ongoing development, this page may be out of date with all current error codes. 

Parser errors are thrown when the Cloth parser panics during compilation. All parser errors are identified with a 'P'
followed by a unique error code, typically three digits. All parser errors are in base 16.

## P001
`P001` is thrown when the parser sees another module declaration after one has already been declared. Each Cloth Object may
only ever have one module declaration. 

## P002
`P002` is thrown when the module declaration is not the first statement in the file. All modul declarations must be the
first statement, comments and whitespace are allowed to precede the module declaration.

## P003
`P003` is thrown when a semicolon `;` is missing from the end of a statement. All statements must end with a semicolon.

## P004
`P004` is thrown when an identifier is expected, but a different token is found, I.E., a literal, keyword, or operator.

In a module declaration, if the file is in the root source directory, you can either place the file in a directory with the
same name as the module, or you can name the module `_src`. `_src` will only ever be valid if the file is in the root source
directory.

## P005
`P005` is thrown when a keyword is expected, but a different token is found, I.E., a literal, identifier, or operator.

## P006
`P006` is thrown when the end-of-file (often referred to as EOF) is expected, but there are more tokens remaining. This 
error is almost never thrown unless the parser is in an invalid state or there is an encoding issue with the file.

## P007
`P007` is thrown when an operator is expected, but a different token is found, I.E., a literal, identifier, or keyword.

## P008
`P008` is thrown when a module path is not defined, returning no identifiers before a semicolon.

## P009
`P009` is thrown when `_src` is used as a module name, but the file is not in the root source directory or `_src` is not
the only identifier in the module path.