#ifndef ORBVOCABULARY_H
#define ORBVOCABULARY_H

#include <vector>
#include <string>
#include <iostream>

class ORBVocabulary {
public:
    ORBVocabulary() {}

    bool loadFromTextFile(const std::string& filename) {
        // Stub: Load DBoW2/FBoW vocabulary
        std::cout << "Loading Vocabulary from " << filename << std::endl;
        return true;
    }

    // Stub methods for scoring, etc.
};

#endif // ORBVOCABULARY_H
