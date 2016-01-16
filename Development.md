# Intended Audience #
This document is for programmers who want to contribute changes to the project. If you just want to request a feature or file a bug, but have someone else work on it, please use the "Issues" tab above.

# Providing Source Code #
If you like to provide some code, patches or completly new library functions, you should refer to the following steps:
  * Create your own repository clone (Source - Clones - Create a clone)
  * Pull your clone to your local PC
  * After making your changes, push them back to your clone at Google Code
  * File a code review request

After reviewing your code, we are a able to pull your changes to the jCoAP repository.

# Eclipse IDE #
## Code Style ##
Please keep your coding style consistent with the one in the rest of the project. To do that in Eclipse, import the given code style configuration:
  1. Select Window > Preferences > Java > Code Style
  1. Use Formatter > Import to import {project-root}/android-formatting.xml
  1. Organize Imports > Import to import {project-root}/android.importorder