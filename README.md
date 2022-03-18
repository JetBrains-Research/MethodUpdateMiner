MethodUpdateMiner

Tool that allows mining method changes in code Java projects by analyzing history of Git repositories.

Usage:
1. Path to the file containing absolute paths to the projects, which you want to process, separated with `\n`. 
     
     Example:
     ```
     dir1/project1/
     dir2/project2/
     dir3/project3/
     ```
2. Path to the directory where resulting files with code-comment samples would be written.
3. Path to .JSON file where the statistics on the collected data would be saved.

Example run:
```shell
./run_miner.sh projects.txt outputs/ stats.json
```
