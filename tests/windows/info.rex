/* what the interpreter sees of Windows: printed, not checked yet */
parse source s; say 'source:' s
say 'executable:' .RexxInfo~executable
say 'libraryPath:' .RexxInfo~libraryPath
say 'directory:' directory()
say 'PATH:' left(value('PATH', , 'ENVIRONMENT'), 200)
say 'Path:' left(value('Path', , 'ENVIRONMENT'), 80)
say 'TEMP:' value('TEMP', , 'ENVIRONMENT')
say 'qualify(x.txt):' qualify('x.txt')
